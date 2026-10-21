package dev.justbuild.client;

import com.mojang.math.Axis;
import dev.justbuild.net.BuilderBotsPayload;
import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.BuilderWorksite;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Cosmetic workers: their only world access is loaded client terrain for footing and lighting. */
final class BuilderBots {
    private static final Map<String, Bot> bots = new LinkedHashMap<>();
    private static ClientLevel level;
    private static int tick;
    private static int receivedAt;
    private static long tickAt;
    private static int rendered;
    private static final int WALK_TICKS = 100, CYCLE_TICKS = 180;
    private static final double SPEED = .065;
    private BuilderBots() { }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BuilderBotsPayload.TYPE,
                (payload, context) -> context.client().execute(() -> receive(payload)));
        LevelRenderEvents.COLLECT_SUBMITS.register(BuilderBots::render);
    }

    static void receive(BuilderBotsPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (level != mc.level) { clear(); level = mc.level; }
        receivedAt = tick;
        var ids = new java.util.HashSet<String>();
        for (BuilderWorksite site : payload.sites()) {
            if (!site.area().dimension().equals(mc.level.dimension())) continue;
            ids.add(site.id());
            Bot bot = bots.computeIfAbsent(site.id(), ignored -> new Bot(site));
            if (!bot.site.area().equals(site.area())) bot.relocate(site);
            bot.site = site;
        }
        bots.keySet().retainAll(ids);
    }

    static void clear() { bots.clear(); level = null; tick = 0; receivedAt = 0; rendered = 0; }

    static void tick(Minecraft mc) {
        if (mc.level == null || level != mc.level) { clear(); return; }
        if (mc.isPaused()) return;
        tick++; tickAt = System.nanoTime();
        // A stale server update cannot leave a worker behind indefinitely.
        if (tick - receivedAt > 100) { bots.clear(); return; }
        for (Bot bot : bots.values()) bot.tick(mc.level);
    }

    private static final class Bot {
        BuilderWorksite site;
        Vec3 position, previous;
        float yaw, previousYaw;
        double distance;
        int direction = 1, age, phase = WALK_TICKS, nextFootingSearch;
        boolean walking, hammering;

        Bot(BuilderWorksite site) { this.site = site; }
        void relocate(BuilderWorksite next) { site = next; position = null; previous = null; phase = WALK_TICKS; nextFootingSearch = 0; }

        void tick(ClientLevel world) {
            age++; walking = false; hammering = false;
            if (position == null) {
                if (age < nextFootingSearch) return;
                nextFootingSearch = age + 20;
                // Try several places around the footprint; never read or load an absent chunk.
                double length = perimeter(site.area());
                int offset = Math.floorMod(site.id().hashCode(), 24);
                for (int i = 0; i < 24; i++) {
                    distance = (offset + i) * length / 24;
                    Vec3 candidate = point(site.area(), distance);
                    position = ground(world, candidate.x, candidate.z, candidate.y, 12);
                    if (position != null) break;
                }
                if (position == null) return;
                previous = position; yaw = facing(center(site.area()).subtract(position)); previousYaw = yaw;
            }
            previous = position; previousYaw = yaw;
            if (!site.working()) { phase = WALK_TICKS; return; }
            phase = (phase + 1) % CYCLE_TICKS;
            if (phase < WALK_TICKS) {
                double nextDistance = distance + direction * SPEED;
                Vec3 next = point(site.area(), nextDistance);
                Vec3 grounded = ground(world, next.x, next.z, position.y, 2);
                if (grounded != null && Math.abs(grounded.y - position.y) <= 1.05) {
                    Vec3 delta = grounded.subtract(position);
                    yaw = turn(yaw, facing(delta), 12);
                    position = grounded; distance = nextDistance; walking = true;
                } else {
                    // Stop and work here, then head back along the clear side instead of clipping through terrain.
                    direction = -direction; phase = WALK_TICKS;
                }
            }
            if (!walking) {
                hammering = true;
                yaw = turn(yaw, facing(center(site.area()).subtract(position)), 10);
            }
        }
    }

    /** A continuous route just outside the footprint leaves room for walls and for the hammer stroke. */
    private static Vec3 point(BuildRegion area, double distance) {
        double x0 = area.min().getX() - 1.1, z0 = area.min().getZ() - 1.1;
        double width = area.max().getX() - area.min().getX() + 3.2;
        double depth = area.max().getZ() - area.min().getZ() + 3.2;
        double length = 2 * (width + depth), t = (distance % length + length) % length;
        if (t < width) return new Vec3(x0 + t, area.min().getY(), z0);
        t -= width;
        if (t < depth) return new Vec3(x0 + width, area.min().getY(), z0 + t);
        t -= depth;
        if (t < width) return new Vec3(x0 + width - t, area.min().getY(), z0 + depth);
        return new Vec3(x0, area.min().getY(), z0 + depth - (t - width));
    }
    private static double perimeter(BuildRegion area) {
        return 2 * (area.max().getX() - area.min().getX() + area.max().getZ() - area.min().getZ() + 6.4);
    }
    private static Vec3 center(BuildRegion area) {
        return new Vec3((area.min().getX() + (double) area.max().getX() + 1) / 2, area.min().getY(),
                (area.min().getZ() + (double) area.max().getZ() + 1) / 2);
    }
    private static float facing(Vec3 delta) { return (float) Math.toDegrees(Math.atan2(-delta.x, -delta.z)); }
    private static float turn(float from, float to, float speed) { return from + Mth.clamp(Mth.wrapDegrees(to - from), -speed, speed); }

    private static Vec3 ground(ClientLevel world, double x, double z, double nearY, int range) {
        int bx = Mth.floor(x), bz = Mth.floor(z), baseY = Mth.floor(nearY) - 1;
        for (int cx = Mth.floor(x - .3) >> 4; cx <= Mth.floor(x + .3) >> 4; cx++)
            for (int cz = Mth.floor(z - .3) >> 4; cz <= Mth.floor(z + .3) >> 4; cz++)
                if (!world.hasChunk(cx, cz)) return null;
        for (int offset = 0; offset <= range * 2; offset++) {
            int dy = offset == 0 ? 0 : (offset + 1) / 2 * (offset % 2 == 1 ? 1 : -1);
            BlockPos floor = new BlockPos(bx, baseY + dy, bz);
            if (world.isOutsideBuildHeight(floor)) continue;
            var state = world.getBlockState(floor);
            if (!state.getFluidState().isEmpty()) continue;
            var shape = state.getCollisionShape(world, floor);
            if (shape.isEmpty()) continue;
            double y = floor.getY() + shape.max(Direction.Axis.Y);
            AABB body = new AABB(x - .28, y + .015, z - .28, x + .28, y + 1.6, z + .28);
            if (world.noCollision(body) && world.getFluidState(BlockPos.containing(x, y + .1, z)).isEmpty()) return new Vec3(x, y, z);
        }
        return null;
    }

    static void render(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance(); rendered = 0;
        if (mc.level == null || mc.level != level || bots.isEmpty()) return;
        BuilderBotModel model = BuilderBotModel.get(mc); if (model == null) return;
        float partial = (float) Mth.clamp((System.nanoTime() - tickAt) / 50_000_000.0, 0, 1);
        var camera = context.levelState().cameraRenderState;
        for (Bot bot : bots.values()) {
            if (bot.position == null || bot.position.distanceToSqr(camera.pos) > 112 * 112) continue;
            Vec3 at = bot.previous.lerp(bot.position, partial);
            if (!mc.level.hasChunk(Mth.floor(at.x) >> 4, Mth.floor(at.z) >> 4)) continue;
            var bounds = new AABB(at.x - .9, at.y, at.z - .9, at.x + .9, at.y + 1.8, at.z + .9);
            if (camera.cullFrustum != null && !camera.cullFrustum.isVisible(bounds)) continue;
            var pose = context.poseStack(); pose.pushPose();
            pose.translate(at.x - camera.pos.x, at.y - camera.pos.y, at.z - camera.pos.z);
            pose.rotate(Axis.YP.rotationDegrees(bot.previousYaw + Mth.wrapDegrees(bot.yaw - bot.previousYaw) * partial));
            pose.scale(.05f, .05f, .05f);
            model.submit(context, bot.age + partial, bot.walking, bot.hammering,
                    LightCoordsUtil.getLightCoords(mc.level, BlockPos.containing(at).above()));
            pose.popPose(); rendered++;
        }
    }

    // Package-visible observations for the real Minecraft regression test.
    static int count() { return bots.size(); }
    static int renderedCount() { return rendered; }
    static Vec3 position(String id) { Bot bot = bots.get(id); return bot == null ? null : bot.position; }
    static boolean working(String id) { Bot bot = bots.get(id); return bot != null && bot.site.working(); }
    static boolean hammering(String id) { Bot bot = bots.get(id); return bot != null && bot.hammering; }
    static boolean walking(String id) { Bot bot = bots.get(id); return bot != null && bot.walking; }
}
