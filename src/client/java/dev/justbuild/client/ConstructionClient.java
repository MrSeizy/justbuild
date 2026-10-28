package dev.justbuild.client;

import com.google.gson.*;
import com.mojang.math.Axis;
import dev.justbuild.net.*;
import dev.justbuild.world.BlockResolver;
import dev.justbuild.world.ZoneWand;
import java.util.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

/** Compact in-world controls, native ghost targets and a cosmetic worker following server progress. */
final class ConstructionClient {
    record Material(String item, ItemStack icon, String name, int need, int layerNeed, int inventory, int chest) { }
    record State(UUID id, String title, String dimension, String mode, boolean paused, String pauseReason, boolean currentLayer,
            int done, int total, int layer, String status, BlockPos chest, BlockPos next, BlockPos blocked, List<Material> materials,
            List<Material> layerMaterials, int manual, int layerManual, int materialTypes, Vec3 worker, boolean walking, boolean hammering) {
        boolean cobble() { return mode.equals("COBBLE"); }
        /** What the current mode builds from: the linked chest for Cobble, otherwise the player's inventory. */
        int supply(Material m) { return cobble() ? m.chest() : m.inventory(); }
    }
    /** Shortages read as warnings, never in the gold used for headings. */
    static final int SHORT = Glass.DANGER_TEXT, READY = 0xFFA7DBA0;
    private static final int HUD_ROWS = 6;
    private static State state;
    private static String problem;
    /** Materials in the server's order; the displayed lists re-sort them as supplies change. */
    private static List<Material> received = List.of();
    private static Map<BlockPos, BlockState> ghosts = Map.of();
    private static BlockPos hovered;
    private static Vec3 worker, previous;
    private static float yaw;
    private static int age;
    private static long tickAt;
    private ConstructionClient() { }
    static State state() { return state; }
    /** Why a saved project could not be read; it still blocks new plans until the player sets it aside. */
    static String problem() { return problem; }
    static boolean active() { return state != null || problem != null; }
    static String statusText(State s, boolean panel) {
        if (!s.paused() || s.mode().equals("GUIDE") || !s.status().equals("Paused")) return s.status();
        // The panel's highlighted Resume button needs no directions.
        return "Paused" + (s.pauseReason() == null ? "" : " " + s.pauseReason()) + (panel ? "" : " · " + JustBuildClient.panelKey() + " → Resume");
    }
    static boolean here() { Minecraft mc = Minecraft.getInstance(); return state != null && mc.level != null && state.dimension().equals(mc.level.dimension().identifier().toString()); }
    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ConstructionStatePayload.TYPE, (p, c) -> c.client().execute(() -> receive(p.json())));
        ClientPlayNetworking.registerGlobalReceiver(ConstructionGhostPayload.TYPE, (p, c) -> c.client().execute(() -> {
            if (!active()) return;
            Map<BlockPos, BlockState> next = new HashMap<>();
            var palette = p.preview().palette().stream().map(s -> BlockResolver.parse(s).orElse(Blocks.AIR.defaultBlockState())).toList();
            for (var cell : p.preview().cells()) if (cell.kind() != GhostPreviewPayload.REMOVE) next.put(cell.pos(), palette.get(cell.state()));
            ghosts = Map.copyOf(next); FloorPreview.all(); GhostPreview.receive(p.preview());
        }));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("justbuild", "construction"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance(); State s = state;
            if (!here() || mc.player == null || mc.gui.screen() != null || mc.gui.hud.isHidden()) return;
            if (ZoneWand.isWand(mc.player.getMainHandItem()) || ZoneWand.isWand(mc.player.getOffhandItem())) return;
            float scale = (float) Math.max(.75, 1 / mc.getWindow().getGuiScale());
            int max = Math.min(268, (int) ((g.guiWidth() - 14) / scale));
            // A short list with shortages first; the project panel holds the rest.
            int capacity = Math.max(1, Math.min(HUD_ROWS, ((int) ((g.guiHeight() - 42) / scale) - 110) / 11));
            int count = Math.min(capacity, s.layerMaterials().size()), hidden = s.layerMaterials().size() - count;
            var status = mc.font.split(net.minecraft.network.chat.Component.literal(statusText(s, false)), max - 12);
            int statusLines = Math.min(2, status.size()), top = 30 + statusLines * 10;
            int rowsY = top + 25, footerY = rowsY + Math.max(1, count) * 11 + (hidden > 0 ? 11 : 0) + (s.layerManual() > 0 ? 11 : 0) + 3;
            int panelHeight = footerY + 18;
            g.pose().pushMatrix();
            try {
                g.pose().translate(7, 7); g.pose().scale(scale, scale);
                Glass.panel(g, 0, 0, max, panelHeight, 0xBC14161E, 0x28FFFFFF);
                g.text(mc.font, mc.font.plainSubstrByWidth(s.title(), max - 12), 6, 5, Glass.TITLE);
                g.text(mc.font, s.done() + " / " + s.total() + " blocks · " + modeLabel(s.mode()), 6, 16, Glass.MUTED);
                for (int i = 0; i < statusLines; i++) g.text(mc.font, status.get(i), 6, 28 + i * 10, Glass.TEXT);
                g.text(mc.font, mc.font.plainSubstrByWidth("Layer Y " + s.layer() + " · materials left", max - 12), 6, top + 2, Glass.TITLE);
                int needX = max - 58, supplyX = max - 6;
                g.text(mc.font, "Material", 6, top + 14, Glass.MUTED);
                right(g, mc, "Need", needX, top + 14, Glass.MUTED);
                right(g, mc, s.cobble() ? "Chest" : "You", supplyX, top + 14, Glass.MUTED);
                for (int i = 0; i < count; i++) {
                    Material m = s.layerMaterials().get(i); int y = rowsY + i * 11;
                    int available = s.supply(m);
                    g.text(mc.font, mc.font.plainSubstrByWidth(m.name(), max - 110), 6, y, Glass.TEXT);
                    right(g, mc, Integer.toString(m.layerNeed()), needX, y, Glass.TEXT);
                    if (s.cobble() && s.chest() == null) right(g, mc, "—", supplyX, y, Glass.MUTED);
                    else right(g, mc, Integer.toString(available), supplyX, y, available >= m.layerNeed() ? READY : SHORT);
                }
                if (count == 0) g.text(mc.font, s.layerManual() < 0 ? "Layer totals unavailable" : s.layerManual() > 0
                        ? "Manual preparation needed" : "No materials left on this layer", 6, rowsY, Glass.TEXT);
                int noteY = rowsY + Math.max(1, count) * 11;
                if (hidden > 0) { g.text(mc.font, "+" + hidden + " more in the project panel", 6, noteY, Glass.MUTED); noteY += 11; }
                if (s.layerManual() > 0) g.text(mc.font, s.layerManual() + " manual tasks on this layer", 6, noteY, Glass.MUTED);
                g.text(mc.font, JustBuildClient.panelKey() + ": project · all materials", 6, footerY, Glass.MUTED);
                int fill = s.total() == 0 ? 0 : (int) ((max - 12L) * s.done() / s.total());
                g.fill(6, panelHeight - 5, max - 6, panelHeight - 3, 0x554C5261);
                g.fill(6, panelHeight - 5, 6 + fill, panelHeight - 3, Glass.TITLE);
            } finally { g.pose().popMatrix(); }
            if (hovered != null && s.mode().equals("ASSIST") && !s.paused()) {
                BlockState target = ghosts.get(hovered);
                if (target != null) {
                    String hint = target.getBlock().getName().getString() + " · " + mc.options.keyUse.getTranslatedKeyMessage().getString() + " to place";
                    int width = mc.font.width(hint) + 12, x = (g.guiWidth() - width) / 2, y = g.guiHeight() / 2 + 22;
                    Glass.panel(g, x, y, width, 19, 0xCA14161E, 0x28FFFFFF); g.text(mc.font, hint, x + 6, y + 6, Glass.TITLE);
                }
            }
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(ConstructionClient::render);
    }
    private static void right(net.minecraft.client.gui.GuiGraphicsExtractor g, Minecraft mc, String text, int x, int y, int color) {
        g.text(mc.font, text, x - mc.font.width(text), y, color);
    }
    static String modeLabel(String mode) { return switch (mode) { case "ASSIST" -> "Assist"; case "COBBLE" -> "Cobble"; default -> "Guide"; }; }
    static void receive(String json) {
        Minecraft mc = Minecraft.getInstance();
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("id")) {
                boolean wasProblem = problem != null;
                clear(); problem = o.has("problem") ? o.get("problem").getAsString() : null;
                if (mc.gui.screen() instanceof ConstructionScreen screen) { if (problem == null) mc.gui.setScreen(null); else if (!wasProblem) screen.stateChanged(); }
                return;
            }
            problem = null;
            boolean fresh = state == null || !state.id().toString().equals(o.get("id").getAsString());
            String mode = o.get("mode").getAsString();
            List<Material> materials;
            // The server leaves out an unchanged list.
            if (!o.has("materials")) materials = fresh ? List.of() : received;
            else {
                materials = new ArrayList<>();
                JsonArray a = o.getAsJsonArray("materials"); if (a.size() > 4096) throw new IllegalArgumentException();
                for (var value : a) {
                    JsonObject m = value.getAsJsonObject();
                    String item = m.has("item") ? m.get("item").getAsString() : "";
                    Identifier key = Identifier.tryParse(item);
                    ItemStack icon = key != null && BuiltInRegistries.ITEM.containsKey(key) ? BuiltInRegistries.ITEM.getValue(key).getDefaultInstance() : ItemStack.EMPTY;
                    // Names come from this client's language; the server's English name is only a fallback.
                    materials.add(new Material(item, icon, icon.isEmpty() ? m.get("name").getAsString() : icon.getHoverName().getString(),
                        m.get("need").getAsInt(), m.has("layerNeed") ? m.get("layerNeed").getAsInt() : 0,
                        m.get("inventory").getAsInt(), m.get("chest").getAsInt())); }
                materials = List.copyOf(materials);
            }
            received = materials;
            boolean cobble = mode.equals("COBBLE");
            List<Material> whole = materials.stream().sorted(Comparator.comparing(m -> (cobble ? m.chest() : m.inventory()) >= m.need())).toList();
            List<Material> layer = materials.stream().filter(m -> m.layerNeed() > 0)
                    .sorted(Comparator.comparing(m -> (cobble ? m.chest() : m.inventory()) >= m.layerNeed())).toList();
            JsonArray w = o.has("worker") ? o.getAsJsonArray("worker") : null;
            state = new State(UUID.fromString(o.get("id").getAsString()), o.get("title").getAsString(), o.get("dimension").getAsString(),
                    mode, o.get("paused").getAsBoolean(), o.has("pauseReason") ? o.get("pauseReason").getAsString() : null, o.get("currentLayer").getAsBoolean(),
                    o.get("done").getAsInt(), o.get("total").getAsInt(), o.get("layer").getAsInt(), o.get("status").getAsString(),
                    pos(o, "chest"), pos(o, "target"), pos(o, "blocked"), whole, layer,
                    o.get("manual").getAsInt(), o.has("layerManual") ? o.get("layerManual").getAsInt() : -1, o.get("materialTypes").getAsInt(),
                    w == null ? null : new Vec3(w.get(0).getAsDouble(), w.get(1).getAsDouble(), w.get(2).getAsDouble()),
                    o.has("walking") && o.get("walking").getAsBoolean(), o.has("hammering") && o.get("hammering").getAsBoolean());
            if (fresh) { ghosts = Map.of(); worker = null; previous = null; }
            if (fresh && (mc.gui.screen() instanceof JustBuildScreen || mc.gui.screen() instanceof ConstructionSetupScreen)) mc.gui.setScreen(new ConstructionScreen());
            else if (mc.gui.screen() instanceof ConstructionScreen screen) screen.stateChanged();
            else if (mc.gui.screen() instanceof ConstructionMaterialsScreen screen) screen.stateChanged();
        } catch (RuntimeException e) { clear(); }
    }
    private static BlockPos pos(JsonObject o, String key) {
        if (!o.has(key)) return null; JsonArray a = o.getAsJsonArray(key); return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }
    static void clear() { if (state != null) GhostPreview.clear(); state = null; problem = null; received = List.of(); ghosts = Map.of(); hovered = null; worker = null; previous = null; age = 0; }
    static void beforeTick(Minecraft mc) {
        hovered = target(mc);
        if (hovered == null || !here() || state.paused() || !state.mode().equals("ASSIST") || mc.player == null
                || mc.gui.screen() != null || mc.player.isShiftKeyDown() || ZoneWand.isWand(mc.player.getMainHandItem())) return;
        boolean clicked = false; while (mc.options.keyUse.consumeClick()) clicked = true;
        mc.options.keyUse.setDown(false);
        if (clicked && ClientPlayNetworking.canSend(ConstructionAssistPayload.TYPE))
            ClientPlayNetworking.send(new ConstructionAssistPayload(state.id(), hovered));
    }
    static BlockPos target(Minecraft mc) {
        if (!here() || mc.player == null || mc.gui.screen() != null) return null;
        if (ZoneWand.isWand(mc.player.getMainHandItem()) || ZoneWand.isWand(mc.player.getOffhandItem())) return null;
        Vec3 eye = mc.player.getEyePosition(), end = eye.add(mc.player.getViewVector(1).scale(mc.player.blockInteractionRange()));
        double reach = Math.min(16, eye.distanceTo(end));
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) reach = Math.min(reach, eye.distanceTo(mc.hitResult.getLocation()) + .01);
        Vec3 direction = end.subtract(eye).normalize(); BlockPos previous = null;
        for (double distance = 0; distance <= reach; distance += .04) {
            BlockPos pos = BlockPos.containing(eye.add(direction.scale(distance)));
            if (pos.equals(previous)) continue; previous = pos;
            BlockState expected = ghosts.get(pos);
            if (expected != null && !expected.equals(mc.level.getBlockState(pos))) return pos;
        }
        return null;
    }
    static void tick(Minecraft mc) {
        if (!here() || mc.isPaused()) return;
        tickAt = System.nanoTime(); age++;
        if (mc.gui.screen() == null && (hovered != null || state.blocked() != null)) try (var ignored = mc.collectPerTickGizmos()) {
            if (hovered != null) Gizmos.cuboid(new AABB(hovered).inflate(.005), GizmoStyle.stroke(0xFFFFCF75));
            // The cell the status talks about, so the player can find what needs their hands.
            if (state.blocked() != null && !state.blocked().equals(hovered)) Gizmos.cuboid(new AABB(state.blocked()).inflate(.02), GizmoStyle.stroke(0xFFFF6B6B));
        }
        if (state.worker() == null) { worker = null; previous = null; return; }
        if (worker == null) worker = state.worker(); previous = worker;
        Vec3 delta = state.worker().subtract(worker);
        if (delta.length() > .01) { worker = worker.add(delta.scale(.35)); yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)); }
        else if (state.next() != null) { Vec3 facing = Vec3.atCenterOf(state.next()).subtract(worker); yaw = (float) Math.toDegrees(Math.atan2(-facing.x, facing.z)); }
    }
    private static void render(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (!here() || worker == null || previous == null || !state.mode().equals("COBBLE")) return;
        var camera = context.levelState().cameraRenderState;
        if (worker.distanceToSqr(camera.pos) > 112 * 112) return;
        BuilderBotModel model = BuilderBotModel.get(mc); if (model == null) return;
        float partial = (float) Math.clamp((System.nanoTime() - tickAt) / 50_000_000.0, 0, 1);
        Vec3 at = previous.lerp(worker, partial);
        if (!mc.level.hasChunk((int) Math.floor(at.x) >> 4, (int) Math.floor(at.z) >> 4)) return;
        var pose = context.poseStack(); pose.pushPose();
        pose.translate(at.x - camera.pos.x, at.y - camera.pos.y, at.z - camera.pos.z);
        pose.rotate(Axis.YP.rotationDegrees(yaw)); pose.scale(.05f, .05f, .05f);
        model.submit(context, age + partial, !state.paused() && state.walking(), !state.paused() && state.hammering(),
                LightCoordsUtil.getLightCoords(mc.level, BlockPos.containing(at).above()));
        pose.popPose();
    }
}
