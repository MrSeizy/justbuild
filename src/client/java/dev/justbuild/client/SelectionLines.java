package dev.justbuild.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.justbuild.world.ZoneWand;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

/** Selection strokes with constant pixel width and an alpha-smoothed edge, visible through terrain. */
final class SelectionLines {
    private static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder(
                    RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("justbuild", "pipeline/selection_lines"))
            .withVertexShader(Identifier.fromNamespaceAndPath("justbuild", "core/selection_lines"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("justbuild", "core/selection_lines"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, VertexFormat.Mode.LINES)
            .withCull(false)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(Optional.empty())
            .build());
    private static final RenderType TYPE = RenderType.create("justbuild_selection_lines", RenderSetup.builder(PIPELINE)
            .bufferSize(RenderType.SMALL_BUFFER_SIZE).createRenderSetup());
    private record Line(Vec3 start, Vec3 end, int color, float width) { }
    private static final List<Line> pending = new ArrayList<>();
    private static List<Line> current = List.of();
    private static ClientLevel level;

    private SelectionLines() { }

    static void register() { LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(SelectionLines::render); }
    static void beginTick() { pending.clear(); }
    static void endTick(Minecraft mc) { current = List.copyOf(pending); level = mc.level; }
    static void clear() { pending.clear(); current = List.of(); level = null; }

    static void line(Vec3 start, Vec3 end, int color, float width) {
        if (start.distanceToSqr(end) > 1.0e-12) pending.add(new Line(start, end, color, width));
    }

    /** A zero-height box is a single perimeter, without doubled edges or tiny vertical strokes. */
    static void box(AABB b, int color, float width) {
        perimeter(b, b.minY, color, width);
        if (b.maxY == b.minY) return;
        perimeter(b, b.maxY, color, width);
        for (double x : new double[] {b.minX, b.maxX}) for (double z : new double[] {b.minZ, b.maxZ})
            line(new Vec3(x, b.minY, z), new Vec3(x, b.maxY, z), color, width);
    }

    private static void perimeter(AABB b, double y, int color, float width) {
        Vec3 a = new Vec3(b.minX, y, b.minZ), c = new Vec3(b.maxX, y, b.maxZ);
        Vec3 b0 = new Vec3(b.maxX, y, b.minZ), d = new Vec3(b.minX, y, b.maxZ);
        line(a, b0, color, width); line(b0, c, color, width);
        line(c, d, color, width); line(d, a, color, width);
    }

    static void render(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != level || mc.player == null || current.isEmpty()
                || !ZoneWand.isWand(mc.player.getMainHandItem()) && !ZoneWand.isWand(mc.player.getOffhandItem())) return;
        var camera = context.levelState().cameraRenderState;
        var pose = context.poseStack().last();
        var buffer = context.bufferSource().getBuffer(TYPE);
        for (Line line : current) {
            // Keep positions camera-relative before converting to floats, including far from the world origin.
            Vec3 a = line.start().subtract(camera.pos), b = line.end().subtract(camera.pos);
            double az = viewZ(camera.viewRotationMatrix, a), bz = viewZ(camera.viewRotationMatrix, b);
            double near = -Camera.PROJECTION_Z_NEAR;
            if (az > near && bz > near) continue;
            if (az > near || bz > near) {
                Vec3 clipped = a.lerp(b, (near - az) / (bz - az));
                if (az > near) a = clipped; else b = clipped;
            }
            if (a.distanceToSqr(b) < 1.0e-12) continue;
            Vec3 direction = b.subtract(a).normalize();
            buffer.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(line.color())
                    .setNormal(pose, (float) direction.x, (float) direction.y, (float) direction.z).setLineWidth(line.width());
            buffer.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(line.color())
                    .setNormal(pose, (float) direction.x, (float) direction.y, (float) direction.z).setLineWidth(line.width());
        }
        context.bufferSource().endBatch(TYPE);
    }

    private static double viewZ(Matrix4fc view, Vec3 p) {
        return view.m02() * p.x + view.m12() * p.y + view.m22() * p.z + view.m32();
    }
}
