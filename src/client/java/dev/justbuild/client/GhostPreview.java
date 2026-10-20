package dev.justbuild.client;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.justbuild.net.GhostPreviewPayload;
import dev.justbuild.world.BlockResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Draws translucent native block models without ever writing to the client world. */
final class GhostPreview {
    private record Ghost(BlockPos pos, BlockState state, byte kind, int hiddenFaces, AABB bounds) { }
    private record Section(AABB bounds, List<Ghost> ghosts) { }
    private record AtlasGroup(net.minecraft.resources.Identifier atlas, List<BakedQuad> quads) { }
    private record Model(List<BakedQuad> quads, Map<Integer, List<AtlasGroup>> visible) { }
    private static List<Section> sections = List.of();
    private static final Direction[] DIRECTIONS = Direction.values();
    private static String dimension = "";
    private static final Map<BlockState, Model> models = new HashMap<>();
    private static Object modelSet;
    /** Optional profiler used by the opt-in preview benchmark; absent during normal play. */
    static java.util.function.LongConsumer renderNanos;
    enum Filter { ALL, ADDED, REPLACED, REMOVED }
    private static Filter filter = Filter.ALL;
    private static boolean before;
    private static Map<BlockPos, Byte> changes = Map.of();
    static Filter filter() { return filter; }
    static void filter(Filter value) { filter = value; }
    static boolean before() { return before; }
    static void before(boolean value) { before = value; }
    static boolean accepts(BlockPos pos) { Byte kind = changes.get(pos); return filter == Filter.ALL || kind != null && accepts(kind); }
    private static boolean accepts(byte kind) {
        return filter == Filter.ALL || filter == Filter.ADDED && kind == GhostPreviewPayload.ADD
                || filter == Filter.REPLACED && kind == GhostPreviewPayload.REPLACE || filter == Filter.REMOVED && kind == GhostPreviewPayload.REMOVE;
    }
    static long count(byte kind) { return changes.values().stream().filter(k -> k == kind).count(); }

    private GhostPreview() { }

    /** Kept even when the player is in another dimension; {@link #active} draws it only in the preview's own. */
    static void receive(GhostPreviewPayload payload) {
        if (Minecraft.getInstance().level == null) return;
        List<BlockState> palette = payload.palette().stream()
                .map(s -> BlockResolver.parse(s).orElse(Blocks.AIR.defaultBlockState())).toList();
        Map<BlockPos, BlockState> solids = new HashMap<>();
        for (var cell : payload.cells()) if (cell.kind() != GhostPreviewPayload.REMOVE) {
            BlockState state = palette.get(cell.state());
            if (state.isSolidRender()) solids.put(cell.pos(), state);
        }
        var kinds = new HashMap<BlockPos, Byte>(); payload.cells().forEach(cell -> kinds.put(cell.pos(), cell.kind()));
        Map<Long, List<Ghost>> grouped = new java.util.LinkedHashMap<>();
        for (var cell : payload.cells()) {
            BlockState state = palette.get(cell.state());
            if (state.isAir()) continue;
            int hidden = 0;
            for (Direction direction : DIRECTIONS) {
                // A removal is hidden on a side where another change is: inside a removed volume only the shell is marked.
                BlockPos next = cell.pos().relative(direction);
                if (cell.kind() == GhostPreviewPayload.REMOVE ? kinds.containsKey(next) : solids.containsKey(next))
                    hidden |= 1 << direction.ordinal();
            }
            long section = net.minecraft.core.SectionPos.asLong(cell.pos());
            grouped.computeIfAbsent(section, ignored -> new ArrayList<>())
                    .add(new Ghost(cell.pos(), state, cell.kind(), hidden, new AABB(cell.pos()).inflate(0.003)));
        }
        sections = grouped.entrySet().stream().map(entry -> {
            BlockPos origin = net.minecraft.core.SectionPos.of(entry.getKey()).origin();
            return new Section(new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 16,
                    origin.getY() + 16, origin.getZ() + 16).inflate(0.003), List.copyOf(entry.getValue()));
        }).toList();
        changes = dev.justbuild.world.PositionMaps.frozen(kinds);
        filter = Filter.ALL; before = false; capNoted = false;
        dimension = payload.dimension();
        models.clear();
    }

    static void clear() {
        sections = List.of();
        FloorPreview.clear();
        models.clear();
        dimension = "";
        changes = Map.of(); filter = Filter.ALL; before = false; capNoted = false;
    }

    private static boolean active(Minecraft mc) {
        return mc.level != null && mc.level.dimension().identifier().toString().equals(dimension);
    }

    /**
     * Outline markers drawn per tick. A removal marker is over 160 vertices and Minecraft's buffer holds 16,777,215, so
     * 100,000 removals in view crashed the game; this keeps every preview far below that.
     */
    static final int MAX_MARKERS = 40_000;
    /** Whether this preview already said that some outlines were left out. */
    private static boolean capNoted;

    static void tick(Minecraft mc) {
        if (!active(mc) || mc.player == null || before) return;
        refreshModels(mc);
        int markers = 0;
        try (var collecting = mc.collectPerTickGizmos()) {
            for (Section section : sections) {
                if (section.bounds().minY > FloorPreview.ceiling() || section.bounds().distanceToSqr(mc.player.position()) > 128 * 128) continue;
                for (Ghost ghost : section.ghosts()) {
                if (!accepts(ghost.kind())) continue;
                if (ghost.pos().getY() > FloorPreview.ceiling() || filter == Filter.ALL && ghost.hiddenFaces() == 63 && ghost.pos().getY() != FloorPreview.ceiling()) continue;
                if (mc.player.distanceToSqr(ghost.pos().getX() + .5, ghost.pos().getY() + .5, ghost.pos().getZ() + .5) > 128 * 128) continue;
                boolean marked = ghost.kind() != GhostPreviewPayload.ADD || model(mc, ghost.state()).quads().isEmpty();
                if (marked && markers++ >= MAX_MARKERS) continue;
                if (ghost.kind() == GhostPreviewPayload.REMOVE) {
                    Gizmos.cuboid(ghost.bounds(), GizmoStyle.fill(0x44FF4444));
                    Gizmos.cuboid(ghost.bounds(), GizmoStyle.stroke(0xFFFF5555, 2)).setAlwaysOnTop();
                    cross(ghost.bounds());
                } else if (ghost.kind() == GhostPreviewPayload.REPLACE) {
                    Gizmos.cuboid(ghost.bounds(), GizmoStyle.stroke(0xFFFFCC44, 1.5f));
                } else if (model(mc, ghost.state()).quads().isEmpty()) {
                    // Fluids and blocks with special renderers still have a visible placement marker.
                    Gizmos.cuboid(ghost.bounds(), GizmoStyle.fill(0x6655FFAA));
                    Gizmos.cuboid(ghost.bounds(), GizmoStyle.stroke(0xFF55FFAA, 1));
                }
                }
            }
        }
        // Once per preview: a thin wall being torn down could otherwise lose outlines without the player knowing.
        if (markers > MAX_MARKERS && !capNoted) {
            capNoted = true;
            var line = dev.justbuild.command.Chat.info(String.format(java.util.Locale.ROOT, "Outlining %,d of %,d nearby "
                    + "changes; the rest aren't marked. The preview box still covers the whole plan.", MAX_MARKERS, markers));
            mc.player.sendSystemMessage(line);
            ClientState.addLine(line);
        }
    }

    /** Removals are marked by shape as well as red: corner-to-corner lines read as an X from any side. */
    private static void cross(AABB b) {
        Gizmos.line(new Vec3(b.minX, b.minY, b.minZ), new Vec3(b.maxX, b.maxY, b.maxZ), 0xFFFF5555, 1.5f).setAlwaysOnTop();
        Gizmos.line(new Vec3(b.maxX, b.minY, b.minZ), new Vec3(b.minX, b.maxY, b.maxZ), 0xFFFF5555, 1.5f).setAlwaysOnTop();
        Gizmos.line(new Vec3(b.minX, b.minY, b.maxZ), new Vec3(b.maxX, b.maxY, b.minZ), 0xFFFF5555, 1.5f).setAlwaysOnTop();
        Gizmos.line(new Vec3(b.maxX, b.minY, b.maxZ), new Vec3(b.minX, b.maxY, b.minZ), 0xFFFF5555, 1.5f).setAlwaysOnTop();
    }

    static void render(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (!active(mc) || before) return;
        long started = renderNanos == null ? 0 : System.nanoTime();
        refreshModels(mc);
        var camera = context.levelState().cameraRenderState;
        var pose = context.poseStack();
        for (Section section : sections) {
            if (section.bounds().minY > FloorPreview.ceiling() || section.bounds().distanceToSqr(camera.pos) > 128 * 128
                    || camera.cullFrustum != null && !camera.cullFrustum.isVisible(section.bounds())) continue;
            for (Ghost ghost : section.ghosts()) {
            if (!accepts(ghost.kind())) continue;
            if (ghost.pos().getY() > FloorPreview.ceiling() || filter == Filter.ALL && ghost.hiddenFaces() == 63 && ghost.pos().getY() != FloorPreview.ceiling() || ghost.kind() == GhostPreviewPayload.REMOVE || camera.pos.distanceToSqr(ghost.pos().getX() + .5, ghost.pos().getY() + .5, ghost.pos().getZ() + .5) > 128 * 128
                    || camera.cullFrustum != null && !camera.cullFrustum.isVisible(ghost.bounds())) continue;
            int hidden = filter == Filter.ALL && ghost.state().isSolidRender() ? ghost.hiddenFaces() : 0;
            if (ghost.pos().getY() == FloorPreview.ceiling()) hidden &= ~(1 << Direction.UP.ordinal());
            List<AtlasGroup> groups = groups(model(mc, ghost.state()), hidden);
            if (groups.isEmpty()) continue;
            pose.pushPose();
            pose.translate(ghost.pos().getX() - camera.pos.x - 0.002,
                    ghost.pos().getY() - camera.pos.y - 0.002, ghost.pos().getZ() - camera.pos.z - 0.002);
            pose.scale(1.004f, 1.004f, 1.004f);
            // Material atlases can differ for modded blocks; submit each atlas separately.
            for (AtlasGroup group : groups) context.submitNodeCollector().submitCustomGeometry(pose,
                    RenderTypes.entityTranslucent(group.atlas()), (matrix, consumer) -> {
                        QuadInstance instance = new QuadInstance();
                        instance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
                        instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
                        for (BakedQuad quad : group.quads()) {
                            int color = ghost.kind() == GhostPreviewPayload.REPLACE ? 0x99FFE0AA : 0x99CCFFDD;
                            if (quad.materialInfo().isTinted()) {
                                var tint = mc.getBlockColors().getTintSource(ghost.state(), quad.materialInfo().tintIndex());
                                if (tint != null) color = (color & 0xFF000000) | (tint.color(ghost.state()) & 0xFFFFFF);
                            }
                            instance.setColor(color);
                            consumer.putBakedQuad(matrix, quad, instance);
                        }
                    });
            pose.popPose();
            }
        }
        if (renderNanos != null) renderNanos.accept(System.nanoTime() - started);
    }

    private static void refreshModels(Minecraft mc) {
        Object current = mc.getModelManager().getBlockStateModelSet();
        if (modelSet != current) { models.clear(); modelSet = current; }
    }

    private static Model model(Minecraft mc, BlockState state) {
        return models.computeIfAbsent(state, s -> {
            if (s.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) return new Model(List.of(), new HashMap<>());
            var parts = new ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
            mc.getModelManager().getBlockStateModelSet().get(s).collectParts(RandomSource.create(42), parts);
            List<BakedQuad> result = new ArrayList<>();
            for (var part : parts) {
                result.addAll(part.getQuads(null));
                for (Direction direction : DIRECTIONS) result.addAll(part.getQuads(direction));
            }
            return new Model(List.copyOf(result), new HashMap<>());
        });
    }

    /** At most 64 masks per block state; these immutable lists replace per-block, per-frame grouping. */
    private static List<AtlasGroup> groups(Model model, int hidden) {
        return model.visible().computeIfAbsent(hidden, mask -> {
            Map<net.minecraft.resources.Identifier, List<BakedQuad>> byAtlas = new java.util.LinkedHashMap<>();
            for (BakedQuad quad : model.quads()) {
                if ((mask & (1 << quad.direction().ordinal())) != 0) continue;
                byAtlas.computeIfAbsent(quad.materialInfo().sprite().atlasLocation(), ignored -> new ArrayList<>()).add(quad);
            }
            return byAtlas.entrySet().stream().map(e -> new AtlasGroup(e.getKey(), List.copyOf(e.getValue()))).toList();
        });
    }
}
