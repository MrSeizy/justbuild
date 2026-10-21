package dev.justbuild.client;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.justbuild.JustBuildMod;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector3f;

/** The original Blockbench cubes and textures, with a small procedural walking/hammering rig. */
final class BuilderBotModel {
    private record Texture(Identifier id, float width, float height, boolean emissive) { }
    private record Face(int texture, Vector3f[] vertices, float[] uv, Vector3f normal) { }
    private record Node(String name, Vector3f pivot, Vector3f rotation, List<Face> faces, List<Node> children) { }
    private record Geometry(PoseStack.Pose pose, List<Face> faces) { }
    private static final Identifier MODEL = Identifier.fromNamespaceAndPath("justbuild", "bot/builder_bot.json");
    private static Object generation;
    private static BuilderBotModel cached;
    private final List<Texture> textures;
    private final Node root;

    private BuilderBotModel(List<Texture> textures, Node root) { this.textures = textures; this.root = root; }

    static BuilderBotModel get(Minecraft minecraft) {
        Object current = minecraft.getModelManager().getBlockStateModelSet();
        if (generation == current) return cached;
        generation = current; cached = null;
        try (var reader = minecraft.getResourceManager().getResourceOrThrow(MODEL).openAsReader()) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            List<Texture> textures = new ArrayList<>();
            for (var entry : json.getAsJsonArray("textures")) {
                var t = entry.getAsJsonObject();
                float width = t.get("width").getAsFloat(), height = t.get("height").getAsFloat();
                if (!(width > 0 && height > 0) || textures.size() >= 4) throw new IllegalArgumentException("Invalid bot textures");
                textures.add(new Texture(Identifier.parse(t.get("id").getAsString()), width, height, t.get("emissive").getAsBoolean()));
            }
            cached = new BuilderBotModel(List.copyOf(textures), node(json.getAsJsonObject("root"), textures, 0));
        } catch (Exception e) { JustBuildMod.LOGGER.warn("Could not load the builder bot model: {}", e.toString()); }
        return cached;
    }

    void submit(LevelRenderContext context, float age, boolean walking, boolean hammering, int light) {
        PoseStack pose = context.poseStack();
        List<Geometry> geometry = new ArrayList<>();
        animate(root, pose, age, walking, hammering, geometry);
        for (int i = 0; i < textures.size(); i++) {
            int textureIndex = i; Texture texture = textures.get(i);
            context.submitNodeCollector().submitCustomGeometry(new PoseStack(), texture.emissive()
                            ? RenderTypes.entityTranslucentEmissive(texture.id()) : RenderTypes.entityCutout(texture.id()),
                    (ignored, vertices) -> {
                        for (Geometry part : geometry) for (Face face : part.faces()) {
                            if (face.texture() != textureIndex) continue;
                            draw(vertices, part.pose(), face, texture.emissive() ? LightCoordsUtil.FULL_BRIGHT : light);
                        }
                    });
        }
    }

    private static void animate(Node node, PoseStack pose, float age, boolean walking, boolean hammering, List<Geometry> out) {
        float stride = walking ? (float) Math.sin(age * .65f) : 0;
        // Bend the elbow forward to lift, then straighten it for the faster downstroke.
        float swing = age % 24;
        float lift = hammering ? swing < 16 ? ease(swing / 16) : swing < 21 ? 1 - ease((swing - 16) / 5) : 0 : 0;
        Vector3f r = new Vector3f(node.rotation());
        switch (node.name()) {
            case "body" -> { r.z += walking ? stride * 2 : 0; pose.pushPose(); pose.translate(0, walking ? Math.abs(stride) * .35 : 0, 0); }
            case "head" -> { r.y += hammering ? -12 : (float) Math.sin(age * .035) * 7; r.x += hammering ? 12 : 0; }
            case "arm_right" -> r.set(hammering ? -8 + 23 * lift : 8 - 22 * stride, hammering ? -20 : 0, 10);
            case "forearm_right" -> r.set(hammering ? 80 * lift : 20, 0, 0);
            case "arm_left" -> r.set(8 + 8 * stride, 0, -12);
            case "forearm_left" -> r.set(65, 0, -10);
            case "grass_block" -> r.set(-73, 0, 22);
            case "leg_left" -> r.x += 25 * stride;
            case "leg_right" -> r.x -= 25 * stride;
            default -> { }
        }
        pose.pushPose();
        pose.translate(node.pivot().x, node.pivot().y, node.pivot().z);
        pose.rotate(Axis.ZP.rotationDegrees(r.z)); pose.rotate(Axis.YP.rotationDegrees(r.y)); pose.rotate(Axis.XP.rotationDegrees(r.x));
        pose.translate(-node.pivot().x, -node.pivot().y, -node.pivot().z);
        if (!node.faces().isEmpty()) out.add(new Geometry(pose.last().copy(), node.faces()));
        for (Node child : node.children()) animate(child, pose, age, walking, hammering, out);
        pose.popPose();
        if (node.name().equals("body")) pose.popPose();
    }

    private static float ease(float t) { return t * t * (3 - 2 * t); }

    private static void draw(VertexConsumer target, PoseStack.Pose pose, Face face, int light) {
        float[] uv = face.uv();
        for (int i = 0; i < 4; i++) {
            var vertex = face.vertices()[i];
            target.addVertex(pose, vertex.x, vertex.y, vertex.z).setColor(0xFFFFFFFF)
                    .setUv(i == 0 || i == 3 ? uv[0] : uv[2], i < 2 ? uv[3] : uv[1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, face.normal());
        }
    }

    private static Node node(JsonObject json, List<Texture> textures, int depth) {
        if (depth > 12 || json.getAsJsonArray("cubes").size() > 64 || json.getAsJsonArray("children").size() > 32)
            throw new IllegalArgumentException("Builder bot model is too large");
        List<Face> faces = new ArrayList<>();
        for (var entry : json.getAsJsonArray("cubes")) {
            var cube = entry.getAsJsonObject(); Vector3f lo = vector(cube.getAsJsonArray("from")), hi = vector(cube.getAsJsonArray("to"));
            for (var side : cube.getAsJsonObject("faces").entrySet()) {
                var value = side.getValue().getAsJsonObject(); int index = value.get("texture").getAsInt();
                Texture texture = textures.get(index); var uv = value.getAsJsonArray("uv");
                Vector3f[] points = vertices(side.getKey(), lo, hi);
                Vector3f normal = new Vector3f(points[1]).sub(points[0]).cross(new Vector3f(points[2]).sub(points[1])).normalize();
                faces.add(new Face(index, points, new float[] {uv.get(0).getAsFloat() / texture.width(), uv.get(1).getAsFloat() / texture.height(),
                        uv.get(2).getAsFloat() / texture.width(), uv.get(3).getAsFloat() / texture.height()}, normal));
            }
        }
        List<Node> children = new ArrayList<>();
        for (var child : json.getAsJsonArray("children")) children.add(node(child.getAsJsonObject(), textures, depth + 1));
        return new Node(json.get("name").getAsString(), vector(json.getAsJsonArray("origin")), vector(json.getAsJsonArray("rotation")),
                List.copyOf(faces), List.copyOf(children));
    }

    private static Vector3f vector(JsonArray value) {
        if (value.size() != 3) throw new IllegalArgumentException("Invalid bot coordinate");
        var v = new Vector3f(value.get(0).getAsFloat(), value.get(1).getAsFloat(), value.get(2).getAsFloat());
        if (!v.isFinite()) throw new IllegalArgumentException("Invalid bot coordinate");
        return v;
    }

    private static Vector3f[] vertices(String face, Vector3f a, Vector3f b) {
        float x = a.x, y = a.y, z = a.z, X = b.x, Y = b.y, Z = b.z;
        return switch (face) {
            case "north" -> points(X,y,z, x,y,z, x,Y,z, X,Y,z);
            case "south" -> points(x,y,Z, X,y,Z, X,Y,Z, x,Y,Z);
            case "east" -> points(X,y,Z, X,y,z, X,Y,z, X,Y,Z);
            case "west" -> points(x,y,z, x,y,Z, x,Y,Z, x,Y,z);
            case "up" -> points(x,Y,Z, X,Y,Z, X,Y,z, x,Y,z);
            case "down" -> points(x,y,z, X,y,z, X,y,Z, x,y,Z);
            default -> throw new IllegalArgumentException("Unknown bot cube face");
        };
    }
    private static Vector3f[] points(float... values) {
        Vector3f[] result = new Vector3f[4];
        for (int i = 0; i < 4; i++) result[i] = new Vector3f(values[i * 3], values[i * 3 + 1], values[i * 3 + 2]);
        return result;
    }
}
