package dev.justbuild.client;

import com.google.gson.*;
import java.util.*;
import dev.justbuild.client.ClientState.*;
import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.DesignThumbnail;

/** Validates one received document; the mutable JSON never escapes this short-lived decoder. */
final class ClientStateDecoder {
    private final JsonObject state;
    private final long receivedAt;
    private ClientStateDecoder(JsonObject state, long receivedAt) { this.state = state; this.receivedAt = receivedAt; }
    List<QualityIssue> qualityIssues() {
        List<QualityIssue> out = new ArrayList<>();
        if (state.get("qualityIssues") instanceof JsonArray array && array.size() <= 16) for (var e : array) {
            try { var o = e.getAsJsonObject(); String id = o.get("id").getAsString();
                if (!id.matches("(DOOR|STAIR|SUPPORT|FOUNDATION|LIMIT)(:-?[0-9]{1,8},-?[0-9]{1,4},-?[0-9]{1,8})?")) continue;
                net.minecraft.core.BlockPos p = null; String dimension = "";
                if (o.has("x")) {
                    p = new net.minecraft.core.BlockPos(dev.justbuild.prompt.Footprint.integer(o.get("x")),
                            dev.justbuild.prompt.Footprint.integer(o.get("y")), dev.justbuild.prompt.Footprint.integer(o.get("z")));
                    if (Math.abs((long) p.getX()) > 30_000_000 || Math.abs((long) p.getZ()) > 30_000_000 || Math.abs((long) p.getY()) > 4096) continue;
                    dimension = o.get("dimension").getAsString();
                }
                out.add(new QualityIssue(id, o.get("message").getAsString(), o.get("repair").getAsString(), dimension, p));
            } catch (RuntimeException ignored) { }
        }
        return List.copyOf(out);
    }

    private JsonObject workshop() { return state.get("workshop") instanceof JsonObject w ? w : new JsonObject(); }

    dev.justbuild.provider.UsageTracker.Snapshot usage() {
        try { return dev.justbuild.provider.UsageTracker.Snapshot.parse(state.getAsJsonObject("usage")); }
        catch (RuntimeException e) { return new dev.justbuild.provider.UsageTracker().snapshot(); }
    }

    dev.justbuild.world.BuildZone zone() {
        try { return state.get("zone") instanceof JsonObject z ? dev.justbuild.world.BuildZone.fromJson(z) : null; }
        catch (RuntimeException e) { return null; }
    }

    dev.justbuild.prompt.BuildConstraints constraints() {
        try { return workshop().get("constraints") instanceof JsonObject c ? dev.justbuild.prompt.BuildConstraints.parse(c)
                : dev.justbuild.prompt.BuildConstraints.NONE; }
        catch (RuntimeException e) { return dev.justbuild.prompt.BuildConstraints.NONE; }
    }

    java.util.Map<String, BuildRegion> namedParts() {
        var parts = new java.util.LinkedHashMap<String, BuildRegion>();
        if (workshop().get("namedParts") instanceof JsonObject values && values.size() <= 32) for (var e : values.entrySet()) {
            try { parts.put(dev.justbuild.world.BuildRecord.label(e.getKey()), BuildRegion.parse(e.getValue().getAsJsonObject())); }
            catch (RuntimeException ignored) { }
        }
        return java.util.Collections.unmodifiableMap(parts);
    }

    List<Version> versions() {
        List<Version> out = new ArrayList<>();
        if (workshop().get("versions") instanceof JsonArray array && array.size() <= 32) for (var e : array) {
            try { var o = e.getAsJsonObject(); String id = o.get("id").getAsString();
                if (!dev.justbuild.world.BuildRecord.Version.validId(id)) continue;
                out.add(new Version(id, o.get("number").getAsInt(), o.get("request").getAsString(), o.get("createdAt").getAsLong(),
                        o.get("current").getAsBoolean(), DesignThumbnail.Image.parse(o.getAsJsonObject("thumbnail"))));
            } catch (RuntimeException ignored) { }
        }
        return List.copyOf(out);
    }

    PolygonDraft polygonDraft() {
        try {
            if (!(workshop().get("polygonDraft") instanceof JsonObject o)) return null;
            var a = o.getAsJsonArray("points"); if (a.size() > 32) return null;
            var points = new ArrayList<dev.justbuild.prompt.Footprint.Point>();
            for (var e : a) { var pair = e.getAsJsonArray(); if (pair.size() != 2) return null;
                points.add(new dev.justbuild.prompt.Footprint.Point(dev.justbuild.prompt.Footprint.integer(pair.get(0)),
                        dev.justbuild.prompt.Footprint.integer(pair.get(1)))); }
            int y = dev.justbuild.prompt.Footprint.integer(o.get("y")); if (Math.abs((long) y) > 4096) return null;
            return new PolygonDraft(o.get("dimension").getAsString(), y, List.copyOf(points));
        } catch (RuntimeException e) { return null; }
    }

    String sampledMaterial() { try { return workshop().has("sampled") ? workshop().get("sampled").getAsString() : null; } catch (RuntimeException e) { return null; } }

    String activeProject() { try { return workshop().has("activeProject") ? workshop().get("activeProject").getAsString() : null; } catch (RuntimeException e) { return null; } }

    List<Material> materials() {
        List<Material> out = new ArrayList<>();
        if (workshop().get("palette") instanceof JsonObject palette) for (var e : palette.entrySet()) {
            try { out.add(new Material(e.getKey(), e.getValue().getAsInt())); } catch (RuntimeException ignored) { }
            if (out.size() >= 128) break;
        }
        return List.copyOf(out);
    }

    List<Project> projects() {
        List<Project> out = new ArrayList<>();
        if (workshop().get("projects") instanceof JsonArray projects) for (var e : projects) {
            try {
                var p = e.getAsJsonObject(); var ids = p.getAsJsonArray("builds"); if (ids.size() > 128) continue;
                List<String> builds = new ArrayList<>(); ids.forEach(id -> builds.add(id.getAsString()));
                List<Build> members = new ArrayList<>();
                if (p.get("members") instanceof JsonArray array && array.size() <= 128) for (var member : array) {
                    var m = member.getAsJsonObject(); members.add(new Build(m.get("id").getAsString(), m.get("title").getAsString(), m.get("versions").getAsInt(), ""));
                }
                List<Collaborator> team = new ArrayList<>();
                if (p.get("collaborators") instanceof JsonArray array && array.size() <= 16) for (var c : array) {
                    var o = c.getAsJsonObject(); team.add(new Collaborator(o.get("id").getAsString(), o.get("name").getAsString()));
                }
                out.add(new Project(p.get("id").getAsString(), p.get("name").getAsString(),
                        dev.justbuild.prompt.ProjectTheme.parse(p.getAsJsonObject("theme")), List.copyOf(builds),
                        p.has("buildCount") ? Math.max(0, Math.min(128, p.get("buildCount").getAsInt())) : builds.size(), List.copyOf(members),
                        !p.has("mine") || p.get("mine").getAsBoolean(), p.has("ownerName") ? p.get("ownerName").getAsString() : "",
                        List.copyOf(team)));
            } catch (RuntimeException ignored) { }
            if (out.size() >= 32) break;
        }
        return List.copyOf(out);
    }

    private JsonObject activity() { return state.get("activity") instanceof JsonObject a ? a : new JsonObject(); }

    private String activityText(String key) {
        try { return activity().has(key) ? activity().get(key).getAsString() : null; } catch (RuntimeException e) { return null; }
    }

    String planning() { return activityText("planning"); }

    String question() { return activityText("question"); }

    List<String> questionOptions() {
        List<String> out = new ArrayList<>();
        if (activity().get("options") instanceof JsonArray options && options.size() <= 8)
            options.forEach(o -> { if (o instanceof JsonPrimitive p) out.add(p.getAsString()); });
        return List.copyOf(out);
    }

    String undoWaiting() { return activityText("undo"); }

    String undoTarget() { return activityText("undoTarget"); }

    String placing() { return activityText("placing"); }

    Route route() {
        if (!(workshop().get("route") instanceof JsonObject route)) return null;
        try {
            long expires = receivedAt + Math.max(0, Math.min(120_000, route.get("remainingMillis").getAsLong()));
            var array = route.getAsJsonArray("markers"); var text = route.getAsJsonArray("lines");
            if (array.size() > 256 || text.size() > 16) return null;
            List<net.minecraft.core.BlockPos> markers = new ArrayList<>(); List<String> lines = new ArrayList<>();
            for (var e : array) { var p = e.getAsJsonArray(); if (p.size() == 3) markers.add(new net.minecraft.core.BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt())); }
            text.forEach(e -> lines.add(e.getAsString()));
            return new Route(route.get("dimension").getAsString(), expires, List.copyOf(lines), List.copyOf(markers));
        } catch (RuntimeException e) { return null; }
    }

    List<Design> designs() {
        List<Design> out = new ArrayList<>();
        if (state.get("designs") instanceof JsonArray array) {
            for (JsonElement element : array) {
                try {
                    JsonObject value = element.getAsJsonObject();
                    out.add(new Design(value.get("id").getAsString(), value.get("name").getAsString(),
                            value.has("folder") ? value.get("folder").getAsString() : "",
                            value.get("thumbnail") instanceof JsonObject thumbnail ? DesignThumbnail.Image.parse(thumbnail) : DesignThumbnail.Image.EMPTY));
                } catch (RuntimeException ignored) { }
                if (out.size() >= 64) break;
            }
        }
        return List.copyOf(out);
    }

    List<Locked> locks() {
        List<Locked> out = new ArrayList<>();
        if (state.get("lockedAreas") instanceof JsonArray array) for (var element : array) {
            try {
                JsonObject o = element.getAsJsonObject();
                out.add(new Locked(o.get("id").getAsString(), o.get("name").getAsString(),
                        BuildRegion.parse(o.getAsJsonObject("region")), o.get("mine").getAsBoolean()));
            } catch (RuntimeException ignored) { }
            if (out.size() >= 128) break;
        }
        return List.copyOf(out);
    }

    BuildRegion part() {
        try { return state.get("part") instanceof JsonObject p ? BuildRegion.parse(p) : null; }
        catch (RuntimeException e) { return null; }
    }

    BuildRegion selectedBounds() {
        try { return buildId(state.get("selectedBuild")) && state.get("selectedBounds") instanceof JsonObject b
                ? BuildRegion.parse(b) : null; }
        catch (RuntimeException e) { return null; }
    }

    BuildRegion partOutline() {
        try { return state.get("partOutline") instanceof JsonObject p ? BuildRegion.parse(p) : part(); }
        catch (RuntimeException e) { return part(); }
    }

    Preview preview() {
        if (!(state.get("preview") instanceof JsonObject p)) return null;
        try {
            List<String> checks = new ArrayList<>(), choices = new ArrayList<>();
            if (p.get("quality") instanceof JsonArray a) a.forEach(e -> checks.add(e.getAsString()));
            if (p.get("options") instanceof JsonArray a) a.forEach(e -> choices.add(e.getAsString()));
            if (checks.size() > 16 || choices.size() > 3) return null;
            return new Preview(p.get("title").getAsString(), p.get("movable").getAsBoolean(), List.copyOf(checks),
                    List.copyOf(choices), Math.max(0, Math.min(Math.max(0, choices.size() - 1), p.get("option").getAsInt())),
                    p.get("position").getAsString(), previewNumber(p, "width", 60_000_001), previewNumber(p, "height", 8193),
                    previewNumber(p, "depth", 60_000_001), previewNumber(p, "added", 1_000_000),
                    previewNumber(p, "replaced", 1_000_000), previewNumber(p, "removed", 1_000_000));
        } catch (RuntimeException e) { return null; }
    }

    private int previewNumber(JsonObject p, String key, int max) {
        if (!p.has(key)) return -1; // Missing optional summaries must not invent counts.
        int n = dev.justbuild.prompt.Footprint.integer(p.get(key));
        if (n < 0 || n > max) throw new IllegalArgumentException("Invalid preview count");
        return n;
    }

    boolean flag(String key) {
        try { return state.has(key) && state.get(key).getAsBoolean(); }
        catch (RuntimeException e) { return false; }
    }

    String string(String key, String fallback) {
        JsonElement e = state.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = state.get(key);
        if (e != null && e.isJsonArray()) {
            e.getAsJsonArray().forEach(v -> { if (v instanceof JsonPrimitive p) out.add(p.getAsString()); });
        }
        return List.copyOf(out);
    }

    List<Profile> profiles() {
        List<Profile> out = new ArrayList<>();
        JsonElement e = state.get("profiles");
        if (e != null && e.isJsonArray()) {
            for (JsonElement p : (JsonArray) e) {
                if (out.size() >= 20) break;
                try {
                    JsonObject o = p.getAsJsonObject();
                    out.add(new Profile(o.get("name").getAsString(), o.get("provider").getAsString(),
                            o.get("model").getAsString(), o.get("baseUrl") instanceof JsonPrimitive url ? url.getAsString() : null,
                            o.get("hasKey") instanceof JsonPrimitive key && key.getAsBoolean(),
                            o.get("reasoningEffort") instanceof JsonPrimitive effort ? effort.getAsString() : null));
                } catch (RuntimeException ignored) { }
            }
        }
        return List.copyOf(out);
    }

    List<Build> builds() {
        List<Build> out = new ArrayList<>();
        JsonElement e = state.get("builds");
        if (e != null && e.isJsonArray()) {
            for (JsonElement b : (JsonArray) e) {
                if (out.size() >= 128) break;
                // A malformed entry from the server is skipped; thrown here, it would crash the screen drawing it.
                try {
                    JsonObject o = b.getAsJsonObject();
                    out.add(new Build(o.get("id").getAsString(), o.get("title").getAsString(), o.get("versions").getAsInt(),
                            o.get("request").getAsString(),
                            o.get("elsewhere") instanceof JsonPrimitive where ? where.getAsString() : null));
                } catch (RuntimeException ignored) { }
            }
        }
        return List.copyOf(out);
    }

    static Snapshot decode(String json) {
        JsonObject next = JsonParser.parseString(json).getAsJsonObject();
        if (next.has("selectedBuild") && !buildId(next.get("selectedBuild"))) next.remove("selectedBuild");
        if (next.get("builds") instanceof JsonArray list) {
            JsonArray kept = new JsonArray();
            list.forEach(b -> { if (b.isJsonObject() && buildId(b.getAsJsonObject().get("id"))) kept.add(b); });
            next.add("builds", kept);
        }
        var decoder = new ClientStateDecoder(next, System.currentTimeMillis());
        Map<String, String> strings = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        next.entrySet().forEach(e -> {
            if (e.getValue().isJsonPrimitive()) strings.put(e.getKey(), e.getValue().getAsString());
            else if (e.getValue().isJsonArray()) {
                var items = new ArrayList<String>();
                for (var item : e.getValue().getAsJsonArray()) {
                    if (items.size() >= 256) break;
                    if (item.isJsonPrimitive()) items.add(item.getAsString());
                }
                lists.put(e.getKey(), List.copyOf(items));
            }
        });
        return new Snapshot(Map.copyOf(strings), Map.copyOf(lists),
                decoder.qualityIssues(),
                decoder.usage(),
                decoder.zone(),
                decoder.constraints(),
                decoder.namedParts(),
                decoder.versions(),
                decoder.polygonDraft(),
                decoder.sampledMaterial(),
                decoder.activeProject(),
                decoder.materials(),
                decoder.projects(),
                decoder.planning(),
                decoder.question(),
                decoder.questionOptions(),
                decoder.undoWaiting(),
                decoder.undoTarget(),
                decoder.placing(),
                decoder.route(),
                decoder.designs(),
                decoder.locks(),
                decoder.selectedBounds(),
                decoder.part(),
                decoder.partOutline(),
                decoder.preview(),
                decoder.profiles(),
                decoder.builds());
    }
    private static boolean buildId(JsonElement id) {
        return id != null && id.isJsonPrimitive() && id.getAsString().matches("[0-9a-f]{8}");
    }
}
