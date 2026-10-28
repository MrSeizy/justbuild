package dev.justbuild.construction;

import com.google.gson.*;
import dev.justbuild.net.*;
import dev.justbuild.world.ChangePreview;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.*;

/** Server-owned survival projects. All access is on the server thread; no chunks are loaded on demand. */
public final class ConstructionManager {
    public interface Access {
        boolean allowed(ServerPlayer player);
        boolean cell(ServerPlayer player, BlockPos pos);
        default int maxBlocks() { return dev.justbuild.config.JustBuildConfig.MAX_BLOCKS_LIMIT; }
    }
    private final MinecraftServer server;
    private final ConstructionStore store;
    private final Access access;
    private final Map<UUID, Site> sites = new HashMap<>();
    private final Set<UUID> loaded = new HashSet<>();
    private final Map<UUID, String> problems = new HashMap<>();
    private static final int SCAN_BUDGET = 1024, SELECT_BUDGET = 512, PROBE_LIMIT = 256, RETRY = 40, ROUTE_RETRY = 200, LINK_TICKS = 600;
    private static final int WRITE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private enum Kind { READY, BUILT, PLACED, BLOCKED, SUPPLY, WAIT }
    /** Support and clearing problems let a hand-built layer move on; Cobble steps past every blocked cell. */
    private enum Cause { NONE, MANUAL, OBSTRUCTED, CLEAR, ENTITY, PROTECTED, SAFETY, SUPPORT, ROUTE }
    private record Check(Kind kind, String reason, Cause cause, BlockPos at) {
        static final Check READY = new Check(Kind.READY, null, Cause.NONE, null), BUILT = new Check(Kind.BUILT, "Already built", Cause.NONE, null),
                PLACED = new Check(Kind.PLACED, null, Cause.NONE, null);
        static Check blocked(Cause cause, String reason, BlockPos at) { return new Check(Kind.BLOCKED, reason, cause, at); }
        boolean letsLayerMoveOn() { return kind == Kind.BLOCKED && (cause == Cause.SUPPORT || cause == Cause.CLEAR); }
    }
    /** A cell set aside until its problem clears; {@code index} is its place in the build order. */
    private record Deferral(String reason, BlockPos at, int index, int retryAt) { }

    private static final class Site {
        final ConstructionProject p;
        final Set<BlockPos> done = new HashSet<>();
        final Set<Long> loadedChunks = new HashSet<>();
        final Map<BlockPos, ConstructionMaterials.Unit> units = new HashMap<>();
        final List<ConstructionMaterials.Unit> purchases;
        final Map<BlockPos, Deferral> deferred = new LinkedHashMap<>();
        JsonArray materials = new JsonArray();
        ConstructionMaterials.Remaining remaining;
        int manual, layerManual, materialTypes, materialLayer = Integer.MIN_VALUE, materialRevision = -1, remainingRevision = -1, remainingLayer;
        int scan, first, open, nextPlace, lastAssist = -10, revision, sentRevision = -1, doneBlocks;
        /** Cobble's pass over the order: cells it skipped for supplies stop it from climbing past their course. */
        int cursor, waitY = Integer.MAX_VALUE, passEnded = -1, nextProbe, noticeUntil, linkingUntil;
        boolean scanned, linking;
        String status = "Checking the worksite", waiting, notice, pauseReason, sentState, sentMaterials;
        BlockPos problemAt, noticeAt;
        Vec3 worker, previous;
        BlockPos workerTarget;
        final ArrayDeque<Vec3> route = new ArrayDeque<>();
        boolean walking, hammering;
        Site(ConstructionProject p) {
            this.p = p; Map<BlockPos, ConstructionMaterials.Unit> distinct = new LinkedHashMap<>();
            for (BlockPos pos : p.order) {
                var unit = ConstructionMaterials.unit(p.blueprint, pos);
                units.put(pos, distinct.computeIfAbsent(unit.anchor(), ignored -> unit));
            }
            purchases = List.copyOf(distinct.values());
        }
    }
    public ConstructionManager(MinecraftServer server, Path directory, Access access) {
        this.server = server; this.store = new ConstructionStore(directory); this.access = access;
    }
    private Site site(ServerPlayer player) {
        UUID owner = player.getUUID();
        if (loaded.add(owner)) {
            try {
                ConstructionProject p = store.load(owner);
                if (p != null) { Site s = new Site(p); s.pauseReason = "after the world reloaded"; sites.put(owner, s); }
            } catch (IOException e) {
                problems.put(owner, e.getMessage());
                message(player, "Your construction project could not be read, so its file was kept. Open the JustBuild panel, "
                        + "or use /justbuild construction finish, to set it aside.");
            }
        }
        return sites.get(owner);
    }
    public boolean active(ServerPlayer player) { return player != null && (site(player) != null || problems.containsKey(player.getUUID())); }
    public ConstructionProject project(ServerPlayer player) { Site s = site(player); return s == null ? null : s.p; }
    public void start(ServerPlayer player, ConstructionProject p) throws IOException {
        if (!p.owner.equals(player.getUUID())) throw new IOException("Construction owner does not match the player");
        if (active(player)) throw new IOException("Finish the current construction project first");
        if (!ServerPlayNetworking.canSend(player, ConstructionStatePayload.TYPE)
                || !ServerPlayNetworking.canSend(player, ConstructionGhostPayload.TYPE))
            throw new IOException("Update your JustBuild client to use survival construction");
        store.save(p); sites.put(player.getUUID(), new Site(p)); loaded.add(player.getUUID());
    }
    /** Removes a project that was never handed to the player, without the closing message. */
    public void close(ServerPlayer player) {
        try { store.delete(player.getUUID()); }
        catch (IOException e) { message(player, "Construction: " + e.getMessage()); }
        sites.remove(player.getUUID()); sendEmpty(player);
    }
    public void command(ServerPlayer player, String command) {
        Site s = site(player);
        try {
            if (command.equals("finish")) { finish(player); return; }
            if (s == null) {
                message(player, problems.containsKey(player.getUUID()) ? "Your construction file could not be read. Use /justbuild construction finish to set it aside."
                        : "Choose Guided Build on a new building preview first.");
                return;
            }
            ConstructionProject p = s.p;
            var oldMode = p.mode; boolean oldLayer = p.currentLayer; BlockPos oldChest = p.chest;
            switch (command) {
                case "guide", "assist", "cobble" -> p.mode = ConstructionProject.Mode.valueOf(command.toUpperCase(Locale.ROOT));
                case "pause", "resume" -> {
                    // Restarts always load paused, so there is nothing to save.
                    p.paused = command.equals("pause"); s.pauseReason = null; restartPass(s);
                    send(player, s, false); return;
                }
                case "layer" -> p.currentLayer = !p.currentLayer;
                case "unlink" -> { p.chest = null; s.linking = false; }
                case "chest" -> {
                    if (!player.level().dimension().equals(p.dimension)) throw new IOException("Return to the construction dimension first");
                    BlockPos aimed = player.pick(player.blockInteractionRange(), 0, false) instanceof BlockHitResult hit
                            && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
                    if (aimed == null || !container(player.level(), aimed)) {
                        // The panel holds the mouse, so wait for the next chest or barrel the player opens.
                        s.linking = true; s.linkingUntil = server.getTickCount() + LINK_TICKS; send(player, s, false); return;
                    }
                    p.chest = supplyAt(player, p, aimed); s.linking = false;
                }
                case "status" -> { send(player, s, true); return; }
                default -> { message(player, "Construction: guide, assist, cobble, pause, resume, layer, chest, unlink, finish"); return; }
            }
            try { store.save(p); }
            catch (IOException e) { p.mode = oldMode; p.currentLayer = oldLayer; p.chest = oldChest; throw e; }
            if (oldMode != p.mode) s.noticeUntil = 0;
            if (oldLayer != p.currentLayer) s.revision++;
            restartPass(s); updateMaterials(player, s); send(player, s, oldLayer != p.currentLayer);
        } catch (IOException e) { message(player, "Construction: " + e.getMessage()); }
    }
    private void finish(ServerPlayer player) throws IOException {
        UUID owner = player.getUUID();
        if (problems.containsKey(owner)) {
            Path kept = store.setAside(owner); problems.remove(owner); sites.remove(owner); sendEmpty(player);
            message(player, kept == null ? "Construction project closed." : "Set the unreadable construction file aside as "
                    + kept.getFileName() + ". You can start new plans now.");
            return;
        }
        if (sites.get(owner) == null) { message(player, "No construction project is open."); return; }
        store.delete(owner); sites.remove(owner); sendEmpty(player);
        message(player, "Construction project closed. Every placed block and unused supply stays in the world.");
    }
    /** Pauses automatic work; the first reason sticks so the player learns why it stopped. */
    public void left(ServerPlayer player, String reason) {
        Site s = sites.get(player.getUUID()); if (s != null) pause(s, reason);
    }
    private static void pause(Site s, String reason) {
        if (!s.p.paused) { s.p.paused = true; s.pauseReason = reason; }
    }
    /** An armed Link chest takes the next chest or barrel the player opens. */
    public void used(ServerPlayer player, BlockPos pos) {
        Site s = sites.get(player.getUUID());
        if (s == null || !s.linking) return;
        if (server.getTickCount() > s.linkingUntil || !player.level().dimension().equals(s.p.dimension)) { s.linking = false; return; }
        if (!container(player.level(), pos)) return;
        s.linking = false;
        BlockPos old = s.p.chest;
        try {
            s.p.chest = supplyAt(player, s.p, pos); store.save(s.p);
            message(player, "Linked the supply at " + pos.toShortString() + ".");
        } catch (IOException e) { s.p.chest = old; message(player, "Construction: " + e.getMessage()); }
        restartPass(s); updateMaterials(player, s); send(player, s, false);
    }
    private static boolean container(ServerLevel level, BlockPos pos) {
        return loaded(level, pos) && (level.getBlockEntity(pos) instanceof ChestBlockEntity || level.getBlockEntity(pos) instanceof BarrelBlockEntity);
    }
    private BlockPos supplyAt(ServerPlayer player, ConstructionProject p, BlockPos pos) throws IOException {
        if (new AABB(Vec3.atLowerCornerOf(p.min), Vec3.atLowerCornerOf(p.max.offset(1, 1, 1))).distanceToSqr(Vec3.atCenterOf(pos)) > 32 * 32)
            throw new IOException("Choose a supply chest within 32 blocks of the site");
        if (supply(player, pos) == null) throw new IOException("Use an unlocked, opened chest or barrel that you may access");
        return pos;
    }
    public void tick() {
        int now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Site s = site(player); if (s == null) continue;
            ConstructionProject p = s.p; s.walking = false; s.hammering = false; s.problemAt = null;
            if (s.linking && now > s.linkingUntil) s.linking = false;
            if (!player.level().dimension().equals(p.dimension)) { pause(s, "after you changed dimension"); s.status = "Return to the construction dimension"; }
            else {
                scan(player.level(), s);
                if (p.blueprint.size() > access.maxBlocks()) {
                    pause(s, "because the server lowered its block limit"); s.status = "Paused · blueprint exceeds the current server block limit";
                } else if (!access.allowed(player) || !player.isAlive() || !player.getAbilities().mayBuild) {
                    pause(s, player.isAlive() ? "while building access was unavailable" : "after you died"); s.status = "Paused · building access unavailable";
                } else if (!s.scanned) s.status = "Checking the worksite";
                else {
                    if (p.mode != ConstructionProject.Mode.COBBLE && now % 10 == 0) probeOpen(player, s);
                    probeDeferred(player, s);
                    if (s.first >= p.order.size()) s.status = "Complete · your build is ready";
                    else if (s.linking) s.status = "Open a chest or barrel to link it as the supply";
                    // Guide never places anything, so pausing it has no effect.
                    else if (p.mode == ConstructionProject.Mode.GUIDE) s.status = byHand(s, "Build the highlighted blueprint by hand");
                    else if (p.paused) s.status = "Paused";
                    else if (p.mode == ConstructionProject.Mode.ASSIST) {
                        if (now < s.noticeUntil) { s.status = s.notice; s.problemAt = s.noticeAt; }
                        else s.status = byHand(s, "Aim at a ghost and use to place · sneak for normal use");
                    } else cobble(player, s);
                }
            }
            if (now % 5 == 0) send(player, s, false);
        }
    }
    private static boolean loaded(ServerLevel level, BlockPos p) { return level.hasChunk(p.getX() >> 4, p.getZ() >> 4); }
    public static boolean matches(ServerLevel level, BlockPos p, BlockState desired) {
        if (!loaded(level, p)) return false;
        BlockState current = level.getBlockState(p);
        if (current.equals(desired)) return true;
        if (current.getBlock() != desired.getBlock()) return false;
        // Only derived connections are normalized; facing, axis, halves and counts remain exact.
        return neighborsLoaded(level, p) && current.equals(Block.updateFromNeighbourShapes(desired, level, p));
    }
    private static boolean neighborsLoaded(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) if (!loaded(level, pos.relative(direction))) return false;
        return true;
    }
    private static void scan(ServerLevel level, Site s) {
        int budget = Math.min(SCAN_BUDGET, s.p.order.size());
        for (int i = 0; i < budget; i++) {
            BlockPos pos = s.p.order.get(s.scan);
            long chunk = net.minecraft.world.level.ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
            if (loaded(level, pos) ? s.loadedChunks.add(chunk) : s.loadedChunks.remove(chunk)) s.revision++;
            boolean match = matches(level, pos, s.p.blueprint.get(pos));
            if (mark(s, pos, match) && !match) { s.first = Math.min(s.first, s.scan); s.open = Math.min(s.open, s.scan); }
            if (++s.scan == s.p.order.size()) { s.scan = 0; s.scanned = true; }
        }
        advance(s);
    }
    private static boolean mark(Site s, BlockPos pos, boolean done) {
        if (!(done ? s.done.add(pos) : s.done.remove(pos))) return false;
        if (done) s.deferred.remove(pos);
        if (!s.p.blueprint.get(pos).isAir()) s.doneBlocks += done ? 1 : -1;
        s.revision++; return true;
    }
    /** {@code first} is the first unfinished cell; {@code open} also skips cells set aside until their problem clears. */
    private static void advance(Site s) {
        var order = s.p.order;
        while (s.first < order.size() && s.done.contains(order.get(s.first))) s.first++;
        if (s.open < s.first) s.open = s.first;
        while (s.open < order.size() && (s.done.contains(order.get(s.open)) || s.deferred.containsKey(order.get(s.open)))) s.open++;
    }
    private static void built(Site s, BlockPos pos) { s.units.get(pos).cells().keySet().forEach(cell -> mark(s, cell, true)); advance(s); }
    private void defer(Site s, BlockPos pos, Check c, int index) {
        s.deferred.put(pos, new Deferral(c.reason(), c.at() == null ? pos : c.at(), index,
                server.getTickCount() + (c.cause() == Cause.ROUTE ? ROUTE_RETRY : RETRY)));
        s.revision++; advance(s);
    }
    private static void restartPass(Site s) { s.cursor = s.open; s.waitY = Integer.MAX_VALUE; s.waiting = null; s.passEnded = -1; }
    /** Hand-built modes skip past cells that need the next layer first or a space cleared, so the layer can move on. */
    private void probeOpen(ServerPlayer player, Site s) {
        for (int i = 0; i < 64 && s.open < s.p.order.size(); i++) {
            BlockPos pos = s.p.order.get(s.open);
            Check c = check(player, s, pos, null, false, false);
            if (c.kind() == Kind.BUILT) { built(s, pos); continue; }
            if (!c.letsLayerMoveOn()) return;
            defer(s, pos, c, s.open);
        }
    }
    /** Re-checks set-aside cells without walking to them, and returns any whose problem cleared to the order. */
    private void probeDeferred(ServerPlayer player, Site s) {
        int now = server.getTickCount();
        if (s.deferred.isEmpty() || now < s.nextProbe) return;
        s.nextProbe = now + RETRY;
        boolean cobble = s.p.mode == ConstructionProject.Mode.COBBLE; int from = Integer.MAX_VALUE;
        List<BlockPos> batch = new ArrayList<>();
        for (BlockPos pos : s.deferred.keySet()) { if (batch.size() == PROBE_LIMIT) break; batch.add(pos); }
        for (BlockPos pos : batch) {
            Deferral d = s.deferred.remove(pos);
            if (now >= d.retryAt()) {
                Check c = check(player, s, pos, null, false, cobble);
                if (c.kind() == Kind.BUILT) { built(s, pos); continue; }
                if (c.kind() != Kind.BLOCKED || !cobble && !c.letsLayerMoveOn()) { from = Math.min(from, d.index()); continue; }
                d = new Deferral(c.reason(), c.at() == null ? pos : c.at(), d.index(), now + RETRY);
            }
            s.deferred.put(pos, d); // Rotate so large sets are all re-checked over time.
        }
        if (from != Integer.MAX_VALUE) { s.open = Math.min(s.open, from); advance(s); restartPass(s); s.revision++; }
    }
    private static String byHand(Site s, String normal) {
        if (s.doneBlocks == s.p.totalBlocks) return leftover(s);
        return s.open >= s.p.order.size() && !s.deferred.isEmpty() ? blockedSummary(s) : normal;
    }
    /** Every block stands; only spaces inside the blueprint still hold terrain or plants. */
    private static String leftover(Site s) {
        int n = s.p.order.size() - s.done.size(); BlockPos at = s.p.order.get(s.first); s.problemAt = at;
        return "All blocks placed · clear " + n + (n == 1 ? " space" : " spaces") + " by hand, starting at " + at.toShortString();
    }
    private static String blockedSummary(Site s) {
        Deferral d = s.deferred.values().stream().min(Comparator.comparingInt(Deferral::index)).orElseThrow();
        s.problemAt = d.at(); int more = s.deferred.size() - 1;
        return d.reason() + " at " + d.at().toShortString() + (more > 0 ? " · " + more + " more blocked" : "");
    }
    private Container supply(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        if (pos == null || !loaded(level, pos) || !access.cell(player, pos)) return null;
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity)) return null;
        var container = (RandomizableContainerBlockEntity) entity;
        if (container.isLocked() || container.getLootTable() != null) return null;
        BlockState state = level.getBlockState(pos);
        if (entity instanceof ChestBlockEntity && state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock block
                && state.getValue(BlockStateProperties.CHEST_TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            BlockPos other = net.minecraft.world.level.block.ChestBlock.getConnectedBlockPos(pos, state);
            if (!loaded(level, other) || !access.cell(player, other)
                    || !(level.getBlockEntity(other) instanceof ChestBlockEntity chest) || chest.isLocked() || chest.getLootTable() != null) return null;
            return net.minecraft.world.level.block.ChestBlock.getContainer(block, state, level, pos, true);
        }
        return container;
    }
    /**
     * Cobble's next buildable cell. It steps past cells with their own problem and, within one course, past cells
     * waiting for supplies; it never climbs above a course that still waits for supplies.
     */
    private BlockPos select(ServerPlayer player, Site s, Container inventory) {
        var order = s.p.order; int now = server.getTickCount();
        if (s.cursor < s.open) s.cursor = s.open;
        for (int budget = SELECT_BUDGET; budget > 0; budget--) {
            if (s.cursor >= order.size() || order.get(s.cursor).getY() > s.waitY) {
                if (s.passEnded < 0) s.passEnded = now;
                if (now - s.passEnded < RETRY / 2) { idle(s); return null; }
                restartPass(s);
                if (s.cursor >= order.size()) { s.passEnded = now; idle(s); return null; }
                continue;
            }
            BlockPos pos = order.get(s.cursor);
            if (s.done.contains(pos) || s.deferred.containsKey(pos)) { s.cursor++; continue; }
            Check c = check(player, s, pos, inventory, true, true);
            switch (c.kind()) {
                case READY -> { return pos; }
                case BUILT -> { built(s, pos); s.cursor++; }
                case BLOCKED -> { defer(s, pos, c, s.cursor); s.cursor++; }
                case SUPPLY -> { if (s.waiting == null) s.waiting = c.reason(); s.waitY = Math.min(s.waitY, pos.getY()); s.cursor++; }
                default -> { s.status = c.reason(); return null; }
            }
        }
        s.status = "Cobble · checking the next course"; return null;
    }
    private static void idle(Site s) {
        if (s.waiting != null) {
            s.status = s.waiting + (s.deferred.isEmpty() ? "" : " · " + s.deferred.size() + " blocked");
            if (!s.deferred.isEmpty()) blockedSummary(s);
        } else if (s.doneBlocks == s.p.totalBlocks) s.status = leftover(s);
        else s.status = s.deferred.isEmpty() ? "Cobble · checking the worksite" : blockedSummary(s);
    }
    private void cobble(ServerPlayer player, Site s) {
        Container inventory = supply(player, s.p.chest);
        if (inventory == null) { s.status = s.p.chest == null ? "Link a supply chest in the project panel" : "Supply chest unavailable · check the linked chest"; return; }
        BlockPos target = select(player, s, inventory);
        if (target == null) return;
        if (player.position().distanceToSqr(Vec3.atCenterOf(target)) > 96 * 96) { s.status = "Move within 96 blocks of Cobble"; return; }
        // Work from inside the wall and follow a clear route between purchases.
        double centerX = (s.p.min.getX() + s.p.max.getX() + 1) / 2.0;
        double centerZ = (s.p.min.getZ() + s.p.max.getZ() + 1) / 2.0;
        int dx = (int) Math.signum(centerX - target.getX() - .5), dz = (int) Math.signum(centerZ - target.getZ() - .5);
        if (dx == 0 && dz == 0) dz = 1;
        Vec3 destination = null;
        var unit = s.units.get(target);
        for (int[] offset : new int[][]{{dx, dz}, {0, dz}, {dx, 0}, {1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
            if (offset[0] == 0 && offset[1] == 0) continue;
            Vec3 point = workerFooting(player.level(), target.getX() + .5 + offset[0] * .9,
                    target.getZ() + .5 + offset[1] * .9, target.getY());
            if (point != null && unit.cells().keySet().stream().noneMatch(pos -> new AABB(pos).intersects(body(point)))) { destination = point; break; }
        }
        if (destination == null) { defer(s, target, Check.blocked(Cause.ROUTE, "Clear room for Cobble beside this block", target), s.cursor); return; }
        if (s.worker == null) s.worker = destination;
        if (!target.equals(s.workerTarget)) { s.route.clear(); s.workerTarget = target; }
        if (s.route.isEmpty() && s.worker.distanceToSqr(destination) > .001) {
            Vec3 end = destination; BlockPos start = BlockPos.containing(s.worker), goal = BlockPos.containing(end);
            List<BlockPos> path = ConstructionPath.find(start, goal, s.p.min.offset(-1, -4, -1), s.p.max.offset(1, 3, 1),
                    pos -> workerClear(player.level(), pos.equals(goal) ? end : new Vec3(pos.getX() + .5, pos.getY(), pos.getZ() + .5)));
            if (path.isEmpty()) { defer(s, target, Check.blocked(Cause.ROUTE, "Clear a route for Cobble to this block", target), s.cursor); return; }
            for (BlockPos pos : path) s.route.add(new Vec3(pos.getX() + .5, pos.getY(), pos.getZ() + .5));
            s.route.removeLast(); s.route.add(end);
        }
        if (!s.route.isEmpty()) {
            s.previous = s.worker; Vec3 step = s.route.peekFirst(), delta = step.subtract(s.worker);
            Vec3 next = delta.length() > .12 ? s.worker.add(delta.normalize().scale(.12)) : step;
            if (!workerClear(player.level(), next)) { s.route.clear(); s.status = "Cobble · finding another route"; return; }
            s.worker = next;
            if (s.worker.distanceToSqr(step) < .001) s.route.removeFirst();
            s.walking = delta.length() > .001; s.status = "Cobble · moving along this course"; return;
        }
        if (server.getTickCount() < s.nextPlace) { s.hammering = true; return; }
        Check result = place(player, s, target, inventory, true, true);
        s.nextPlace = server.getTickCount() + 10;
        switch (result.kind()) {
            case PLACED, BUILT -> { s.status = "Cobble · building this course"; s.hammering = result.kind() == Kind.PLACED; }
            case BLOCKED -> defer(s, target, result, s.cursor);
            default -> s.status = result.reason();
        }
    }
    private static Vec3 workerFooting(ServerLevel level, double x, double z, int course) {
        // Stand on the interior floor while the wall is in reach. Tall courses need no player-built scaffold.
        for (int y = course + 1; y >= course - 4; y--) {
            BlockPos floor = BlockPos.containing(x, y - 1, z);
            if (!loaded(level, floor)) continue;
            var shape = level.getBlockState(floor).getCollisionShape(level, floor);
            if (shape.isEmpty()) continue;
            double localX = x - floor.getX(), localZ = z - floor.getZ();
            var surface = shape.toAabbs().stream().filter(b -> localX >= b.minX && localX <= b.maxX && localZ >= b.minZ && localZ <= b.maxZ)
                    .mapToDouble(b -> b.maxY).max();
            if (surface.isEmpty()) continue;
            double foot = floor.getY() + surface.getAsDouble();
            if (workerClear(level, new Vec3(x, foot, z)))
                return new Vec3(x, foot, z);
        }
        Vec3 hovering = new Vec3(x, course, z);
        return workerClear(level, hovering) ? hovering : null;
    }
    private static AABB body(Vec3 at) { return new AABB(at.x - .28, at.y + .01, at.z - .28, at.x + .28, at.y + 1.75, at.z + .28); }
    private static boolean workerClear(ServerLevel level, Vec3 at) {
        AABB box = body(at);
        for (int x = (int) Math.floor(box.minX) >> 4; x <= (int) Math.floor(box.maxX) >> 4; x++)
            for (int z = (int) Math.floor(box.minZ) >> 4; z <= (int) Math.floor(box.maxZ) >> 4; z++)
                if (!level.hasChunk(x, z)) return false;
        return level.noCollision(box);
    }
    public void assist(ServerPlayer player, ConstructionAssistPayload payload) {
        Site s = site(player);
        if (s == null || !s.p.id.equals(payload.project()) || s.p.mode != ConstructionProject.Mode.ASSIST || s.p.paused
                || !s.scanned || !s.p.dimension.equals(player.level().dimension()) || !s.p.blueprint.containsKey(payload.pos())
                || s.p.blueprint.size() > access.maxBlocks()
                || !access.allowed(player) || !player.isAlive() || !player.getAbilities().mayBuild
                || server.getTickCount() - s.lastAssist < 4) return;
        s.lastAssist = server.getTickCount();
        BlockPos target = payload.pos();
        if (s.p.currentLayer && s.first < s.p.order.size() && target.getY() != currentLayer(s)) {
            notice(player, s, "Finish layer Y " + currentLayer(s) + " first, or show all ghosts", null); return;
        }
        Vec3 eye = player.getEyePosition(), end = eye.add(player.getViewVector(1).scale(player.blockInteractionRange()));
        Optional<Vec3> intersection = new AABB(target).clip(eye, end);
        if (intersection.isEmpty() || !player.isWithinBlockInteractionRange(target, 0)) { notice(player, s, "Move closer to place that block", target); return; }
        BlockHitResult hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(target)
                && eye.distanceToSqr(hit.getLocation()) + .01 < eye.distanceToSqr(intersection.get())) {
            notice(player, s, "Something is in front of that block", target); return;
        }
        Check result = place(player, s, target, player.getInventory(), false, false);
        notice(player, s, switch (result.kind()) {
            case PLACED -> "Placed from your inventory";
            case BLOCKED -> result.reason() + " at " + result.at().toShortString();
            default -> result.reason();
        }, result.kind() == Kind.BLOCKED ? result.at() : null);
    }
    /** Assist feedback outlives the next tick's mode status, so the player can read it. */
    private void notice(ServerPlayer player, Site s, String text, BlockPos at) {
        s.notice = text; s.noticeAt = at; s.noticeUntil = server.getTickCount() + 60;
        s.status = text; s.problemAt = at; send(player, s, false);
    }
    /**
     * Whether a purchase can be made now, without changing the world. Inventory is null when supplies don't matter;
     * {@code clear} lets the helper clear grass, snow layers and other replaceable plants from spaces that stay empty.
     */
    private Check check(ServerPlayer player, Site site, BlockPos target, Container inventory, boolean chest, boolean clear) {
        ServerLevel level = player.level();
        var unit = site.units.get(target);
        if (unit.cells().entrySet().stream().allMatch(e -> matches(level, e.getKey(), e.getValue()))) return Check.BUILT;
        boolean space = unit.cells().size() == 1 && unit.cells().get(unit.anchor()).isAir();
        if (unit.manual() != null && !space) return Check.blocked(Cause.MANUAL, unit.manual(), target);
        for (var e : unit.cells().entrySet()) {
            BlockPos pos = e.getKey();
            if (!loaded(level, pos) || !neighborsLoaded(level, pos)) return new Check(Kind.WAIT, "Move closer to load the worksite", Cause.NONE, pos);
            if (!access.cell(player, pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || level.isOutsideBuildHeight(pos)) return Check.blocked(Cause.PROTECTED, "This position is protected", pos);
            BlockState current = level.getBlockState(pos);
            if (matches(level, pos, e.getValue())) return Check.blocked(Cause.MANUAL, "Complete this partly built door or bed by hand", pos);
            BlockPos other = companion(current, pos);
            if (!current.canBeReplaced() || !current.getFluidState().isEmpty() || level.getBlockEntity(pos) != null
                    || other != null && !unit.cells().containsKey(other)
                    && (!loaded(level, other) || !access.cell(player, other) || !level.getBlockState(other).is(current.getBlock())))
                return space ? Check.blocked(Cause.CLEAR, "Clear this space by hand", pos) : Check.blocked(Cause.OBSTRUCTED, "Clear the obstruction", pos);
            if (space) return clear ? Check.READY : Check.blocked(Cause.CLEAR, "Clear this space by hand", pos);
            if (!level.isUnobstructed(e.getValue(), pos, net.minecraft.world.phys.shapes.CollisionContext.empty()))
                return Check.blocked(Cause.ENTITY, "Waiting for the work area to be clear", pos);
        }
        int slots = chest ? Integer.MAX_VALUE : Inventory.INVENTORY_SIZE;
        if (inventory != null && ConstructionMaterials.available(inventory, unit.item(), slots) < unit.count()) {
            String name = new net.minecraft.world.item.ItemStack(unit.item()).getHoverName().getString(), amount = unit.count() > 1 ? unit.count() + " " : "";
            return new Check(Kind.SUPPLY, chest ? "Waiting for " + amount + name + " in the linked chest" : "Need " + amount + name + " in your inventory", Cause.NONE, target);
        }
        var safe = dev.justbuild.world.PlacementSafety.check(unit.cells(), pos -> loaded(level, pos)
                ? level.getBlockState(pos) : Blocks.AIR.defaultBlockState(),
                pos -> site.p.blueprint.containsKey(pos) && access.cell(player, pos));
        if (!safe.placements().equals(unit.cells())) return Check.blocked(Cause.SAFETY, "Prepare this block safely by hand", target);
        // The second half of a door or bed is checked as it is written, once the first half stands.
        if (!unit.cells().get(unit.anchor()).canSurvive(level, unit.anchor()))
            return Check.blocked(Cause.SUPPORT, "Add the required support before this block", unit.anchor());
        return Check.READY;
    }
    /** The other half of a tall replaceable plant, which must go with the half being replaced. */
    private static BlockPos companion(BlockState current, BlockPos pos) {
        if (!current.canBeReplaced() || !current.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return null;
        return current.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
    }
    private Check place(ServerPlayer player, Site site, BlockPos target, Container inventory, boolean chest, boolean clear) {
        Check check = check(player, site, target, inventory, chest, clear);
        if (check.kind() == Kind.BUILT) built(site, target);
        if (check.kind() != Kind.READY) return check;
        ServerLevel level = player.level();
        var unit = site.units.get(target);
        boolean space = unit.cells().get(unit.anchor()).isAir();
        Map<BlockPos, BlockState> writes = new LinkedHashMap<>();
        for (BlockPos pos : unit.cells().keySet()) {
            BlockPos other = companion(level.getBlockState(pos), pos);
            if (other != null && !unit.cells().containsKey(other)) writes.put(other, Blocks.AIR.defaultBlockState());
        }
        writes.putAll(unit.cells());
        // No drops or neighbor writes until the whole purchase is valid. Roll back on a failed paired placement.
        var before = new LinkedHashMap<BlockPos, BlockState>();
        for (var e : writes.entrySet()) {
            BlockState old = level.getBlockState(e.getKey());
            if (!e.getValue().isAir() && !e.getValue().canSurvive(level, e.getKey()) || !level.setBlock(e.getKey(), e.getValue(), WRITE)) {
                List<BlockPos> written = new ArrayList<>(before.keySet());
                for (int i = written.size() - 1; i >= 0; i--) level.setBlock(written.get(i), before.get(written.get(i)), WRITE);
                return Check.blocked(Cause.SUPPORT, "Add the required support before this block", e.getKey());
            }
            before.put(e.getKey(), old);
        }
        if (!space) ConstructionMaterials.consume(inventory, unit.item(), unit.count(), chest ? Integer.MAX_VALUE : Inventory.INVENTORY_SIZE);
        unit.cells().keySet().forEach(pos -> mark(site, pos, true));
        for (BlockPos pos : before.keySet()) {
            // Update only blueprint cells, never an unrelated neighbor across a protected boundary.
            for (Direction direction : Direction.values()) refresh(player, site, pos.relative(direction));
            refresh(player, site, pos);
        }
        site.revision++; advance(site);
        if (space) level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, target, Block.getId(before.get(target)));
        else level.playSound(null, target, unit.cells().get(unit.anchor()).getSoundType().getPlaceSound(), SoundSource.BLOCKS, .6f, .9f);
        player.containerMenu.broadcastChanges();
        return Check.PLACED;
    }
    private void refresh(ServerPlayer player, Site site, BlockPos pos) {
        if (!site.p.blueprint.containsKey(pos) || !loaded(player.level(), pos) || !neighborsLoaded(player.level(), pos) || !access.cell(player, pos)) return;
        BlockState old = player.level().getBlockState(pos);
        BlockState next = Block.updateFromNeighbourShapes(old, player.level(), pos);
        if (!next.isAir() && next != old) player.level().setBlock(pos, next, WRITE);
    }
    public void sync(ServerPlayer player) { Site s = site(player); if (s == null) sendEmpty(player); else send(player, s, false); }
    public void syncNow(ServerPlayer player) { Site s = site(player); if (s == null) sendEmpty(player); else send(player, s, true); }
    /** No project, or one whose file could not be read; the client offers to set that file aside. */
    private void sendEmpty(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, ConstructionStatePayload.TYPE)) return;
        JsonObject o = new JsonObject(); String problem = problems.get(player.getUUID());
        if (problem != null) o.addProperty("problem", problem);
        ServerPlayNetworking.send(player, new ConstructionStatePayload(o.toString()));
    }
    private void send(ServerPlayer player, Site s, boolean force) {
        if (!ServerPlayNetworking.canSend(player, ConstructionStatePayload.TYPE)) return;
        var p = s.p; JsonObject o = new JsonObject(); int now = server.getTickCount();
        o.addProperty("id", p.id.toString()); o.addProperty("title", p.title); o.addProperty("dimension", p.dimension.identifier().toString());
        o.addProperty("mode", p.mode.name()); o.addProperty("paused", p.paused); o.addProperty("currentLayer", p.currentLayer);
        if (p.paused && s.pauseReason != null) o.addProperty("pauseReason", s.pauseReason);
        o.addProperty("done", s.doneBlocks); o.addProperty("total", p.totalBlocks); o.addProperty("status", s.status);
        int layer = currentLayer(s); o.addProperty("layer", layer);
        BlockPos target = p.mode == ConstructionProject.Mode.COBBLE && s.workerTarget != null ? s.workerTarget
                : s.open < p.order.size() ? p.order.get(s.open) : null;
        if (target != null) o.add("target", ConstructionStore.position(target));
        if (s.problemAt != null) o.add("blocked", ConstructionStore.position(s.problemAt));
        if (p.chest != null) o.add("chest", ConstructionStore.position(p.chest));
        if (s.worker != null && p.mode == ConstructionProject.Mode.COBBLE) {
            JsonArray worker = new JsonArray(); worker.add(s.worker.x); worker.add(s.worker.y); worker.add(s.worker.z); o.add("worker", worker);
            o.addProperty("walking", s.walking); o.addProperty("hammering", s.hammering);
        }
        // Material detail changes at human speed. Packets stay bounded even for very large palettes.
        if (force || s.materialRevision != s.revision || s.materialLayer != layer || now % 20 == 0) updateMaterials(player, s);
        String materials = s.materials.toString();
        // Unchanged lists are left out; the client keeps its copy. A periodic full copy repairs a client that lost it.
        if (force || !materials.equals(s.sentMaterials) || now % 100 == 0) o.add("materials", s.materials);
        o.addProperty("manual", s.manual); o.addProperty("materialTypes", s.materialTypes); o.addProperty("layerManual", s.layerManual);
        String json = o.toString();
        if (force || !json.equals(s.sentState)) {
            ServerPlayNetworking.send(player, new ConstructionStatePayload(json)); s.sentState = json; s.sentMaterials = materials;
        }
        int ghostInterval = !p.currentLayer && p.blueprint.size() > 50_000 ? 100 : 20;
        if ((force || s.sentRevision != s.revision && now % ghostInterval == 0)
                && player.level().dimension().equals(p.dimension) && ServerPlayNetworking.canSend(player, ConstructionGhostPayload.TYPE)) {
            Map<BlockPos, BlockState> remaining = new LinkedHashMap<>();
            p.blueprint.forEach((pos, state) -> { if (!s.done.contains(pos) && (!p.currentLayer || pos.getY() == layer) && loaded(player.level(), pos)) remaining.put(pos, state); });
            var ghost = ChangePreview.create(p.dimension.identifier().toString(), remaining, player.level()::getBlockState, GhostPreviewPayload.LARGE_MAX_CELLS);
            ServerPlayNetworking.send(player, new ConstructionGhostPayload(ghost)); s.sentRevision = s.revision;
        }
    }
    /** The lowest course with cells that can be worked on now. */
    private static int currentLayer(Site s) {
        var order = s.p.order;
        return s.open < order.size() ? order.get(s.open).getY() : s.first < order.size() ? order.get(s.first).getY() : s.p.max.getY();
    }
    private void updateMaterials(ServerPlayer player, Site s) {
        var p = s.p;
        int layer = currentLayer(s);
        if (s.remaining == null || s.remainingRevision != s.revision || s.remainingLayer != layer) {
            s.remaining = ConstructionMaterials.remaining(s.purchases, s.done, layer); s.remainingRevision = s.revision; s.remainingLayer = layer;
        }
        var remaining = s.remaining;
        Container supplies = player.level().dimension().equals(p.dimension) ? supply(player, p.chest) : null;
        JsonArray materials = new JsonArray();
        remaining.total().entrySet().stream().sorted(Comparator.comparing(e -> BuiltInRegistries.ITEM.getKey(e.getKey()).toString())).forEach(e -> {
            JsonObject m = new JsonObject(); m.addProperty("item", BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
            m.addProperty("name", new net.minecraft.world.item.ItemStack(e.getKey()).getHoverName().getString()); m.addProperty("need", e.getValue());
            m.addProperty("layerNeed", remaining.layer().getOrDefault(e.getKey(), 0));
            // Assist spends only the 36 storage slots, so worn armor and the offhand never count as supplies.
            m.addProperty("inventory", ConstructionMaterials.available(player.getInventory(), e.getKey(), Inventory.INVENTORY_SIZE));
            m.addProperty("chest", ConstructionMaterials.available(supplies, e.getKey())); materials.add(m);
        });
        s.materials = materials; s.manual = remaining.manual(); s.layerManual = remaining.layerManual();
        s.materialTypes = remaining.total().size(); s.materialLayer = layer; s.materialRevision = s.revision;
    }
    private static void message(ServerPlayer p, String text) { p.sendSystemMessage(Component.literal("[JustBuild] " + text)); }
}
