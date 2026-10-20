package dev.justbuild.session;

import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.prompt.BuildConstraints;
import dev.justbuild.prompt.Footprint;
import dev.justbuild.provider.UsageTracker;
import dev.justbuild.world.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Builds panel documents from presentation inputs; owns no conversations or world mutations. */
final class PanelStateBuilder {
    record Catalog(BuildRegistry registry, Collection<DesignStore.Design> designs, Collection<ProjectStore.Project> projects,
            List<RegionStore.Locked> locks, List<String> importFiles, Function<String, String> playerName,
            Function<BuildRecord.Version, DesignThumbnail.Image> thumbnails) {}
    record Selection(BuildZone zone, boolean zoneSelecting, BuildRegion part, BuildRegion outline, boolean partSelecting,
            boolean partWand, String firstConnection, String secondConnection, String retainedTitle) {}
    record Workshop(BuildConstraints constraints, PolygonView polygon, Map<String, Integer> palette, String sampled,
            String activeProject, RouteReport route) {}
    record PolygonView(ResourceKey<Level> dimension, int y, List<Footprint.Point> points) {}
    record RouteReport(ResourceKey<Level> dimension, Walkability.Result result, Instant expires) {}
    record Activity(String question, List<String> options, String planning, String undo, String undoTarget, String placing) {}
    record View(String actor, ResourceKey<Level> dimension, boolean manage, StateJson.Ai ai, BuildRecord editing,
            Selection selection, Workshop workshop, Activity activity, StateJson.PreviewSummary preview, String collecting,
            UsageTracker.Snapshot usage, List<BuildQuality.Issue> issues, String issueDimension, boolean qualityUndo, boolean autoBuild) {}
    private PanelStateBuilder() {}

    static String build(JustBuildConfig config, View view, Catalog catalog) {
        String actor = view.actor(); var selection = view.selection(); var editing = view.editing();
        var builds = catalog.registry().all().stream().filter(r -> r.owner().equals(actor))
                .sorted(Comparator.comparingLong(BuildRecord::lastChanged).reversed()).limit(128)
                .map(r -> new StateJson.BuildSummary(r.id(), r.title(), r.current().number(),
                        panelRequest(r.versions().getFirst().request()), r.dimension().equals(view.dimension()) ? null : r.dimension().identifier().toString())).toList();
        var tools = new StateJson.Tools(selection.part(), selection.partSelecting(), selection.partWand(),
                !view.manage() ? List.of() : catalog.locks().stream().filter(l -> l.region().dimension().equals(view.dimension()))
                        .map(l -> new StateJson.LockSummary(l.id(), l.name(), l.region(), l.owner().equals(actor))).toList(),
                title(catalog, selection.firstConnection()), title(catalog, selection.secondConnection()),
                view.manage() ? catalog.importFiles() : List.of(), view.preview(), view.collecting(), selection.outline());
        var workspace = new StateJson.Workspace(editing == null ? null : editing.id(), editing == null ? null : editing.title(),
                editing == null ? List.of() : editing.checkpoints().stream().map(BuildRecord.Checkpoint::name).toList(),
                catalog.designs().stream().filter(d -> d.owner().equals(actor)).map(d -> new StateJson.DesignSummary(d.id(), d.name(), d.folder(), d.thumbnail())).toList(),
                selection.retainedTitle(), tools);
        var state = com.google.gson.JsonParser.parseString(StateJson.build(config, builds, view.manage(), view.ai(),
                selection.zone(), selection.zoneSelecting(), workspace)).getAsJsonObject();
        state.add("workshop", workshopState(view, catalog));
        state.add("activity", activityState(view.activity()));
        if (editing != null) {
            BuildRegion bounds = catalog.registry().bounds(editing);
            if (bounds != null) state.add("selectedBounds", bounds.toJson());
        }
        state.add("usage", view.usage().toJson());
        var issues = new com.google.gson.JsonArray();
        for (var issue : view.issues()) {
            var item = new com.google.gson.JsonObject(); item.addProperty("id", issue.id()); item.addProperty("message", issue.message());
            item.addProperty("repair", issue.repair());
            if (issue.position() != null) {
                item.addProperty("x", issue.position().getX()); item.addProperty("y", issue.position().getY()); item.addProperty("z", issue.position().getZ());
                item.addProperty("dimension", view.issueDimension());
            }
            issues.add(item);
        }
        state.add("qualityIssues", issues); state.addProperty("qualityUndo", view.qualityUndo()); state.addProperty("autoBuild", view.autoBuild());
        return state.toString();
    }
    private static String title(Catalog catalog, String id) { return id == null ? null : catalog.registry().get(id).map(BuildRecord::title).orElse(null); }
    private static String panelRequest(String request) { return clip(EditTarget.withoutDirection(request), 600); }
    private static String clip(String text, int max) { return text.length() <= max ? text : text.substring(0, max - 3) + "…"; }
    private static com.google.gson.JsonObject activityState(Activity activity) {
        var value = new com.google.gson.JsonObject();
        if (activity.question() != null) {
            value.addProperty("question", activity.question()); var options = new com.google.gson.JsonArray();
            activity.options().forEach(options::add); value.add("options", options);
        } else if (activity.planning() != null) value.addProperty("planning", clip(activity.planning(), 80));
        if (activity.undo() != null) value.addProperty("undo", activity.undo());
        if (activity.undoTarget() != null) value.addProperty("undoTarget", activity.undoTarget());
        if (activity.placing() != null) value.addProperty("placing", activity.placing());
        return value;
    }
    private static com.google.gson.JsonObject workshopState(View view, Catalog catalog) {
        String actor = view.actor(); var dimension = view.dimension(); BuildRecord editing = view.editing();
        var value = new com.google.gson.JsonObject();
        value.addProperty("adopted", editing != null && editing.adopted());
        value.add("constraints", (editing == null ? view.workshop().constraints() : editing.constraints()).toJson());
        var namedParts = new com.google.gson.JsonObject();
        if (editing != null) editing.namedParts().forEach((name, region) -> namedParts.add(name, region.toJson()));
        value.add("namedParts", namedParts);
        var versions = new com.google.gson.JsonArray();
        if (editing != null) for (var v : editing.versions().reversed().stream().limit(32).toList()) {
            var version = new com.google.gson.JsonObject(); version.addProperty("id", v.id()); version.addProperty("number", v.number());
            version.addProperty("request", clip(v.request(), 120)); version.addProperty("createdAt", v.createdAt());
            version.addProperty("current", v.id().equals(editing.current().id()));
            var thumbnail = catalog.thumbnails().apply(v);
            version.add("thumbnail", thumbnail.toJson()); versions.add(version);
        }
        value.add("versions", versions);
        PolygonView draft = view.workshop().polygon();
        if (draft != null && draft.dimension().equals(dimension)) {
            var polygon = new com.google.gson.JsonObject(); polygon.addProperty("dimension", dimension.identifier().toString());
            polygon.addProperty("y", draft.y()); polygon.add("points", dev.justbuild.prompt.Footprint.json(draft.points()));
            value.add("polygonDraft", polygon);
        }
        var palette = new com.google.gson.JsonObject(); view.workshop().palette().forEach(palette::addProperty);
        value.add("palette", palette);
        if (view.workshop().sampled() != null) value.addProperty("sampled", view.workshop().sampled());
        var list = new com.google.gson.JsonArray();
        for (var p : catalog.projects()) {
            if (!p.team(actor) || !p.dimension().equals(dimension)) continue;
            var project = new com.google.gson.JsonObject(); project.addProperty("id", p.id()); project.addProperty("name", p.name());
            project.addProperty("mine", p.owner().equals(actor));
            if (!p.owner().equals(actor)) project.addProperty("ownerName", catalog.playerName().apply(p.owner()));
            var team = new com.google.gson.JsonArray();
            p.collaborators().forEach((id, name) -> { var c = new com.google.gson.JsonObject(); c.addProperty("id", id);
                c.addProperty("name", name); team.add(c); });
            project.add("collaborators", team);
            project.add("theme", p.theme().toJson()); var ids = new com.google.gson.JsonArray(); var members = new com.google.gson.JsonArray();
            List<BuildRecord> records = p.builds().stream().map(catalog.registry()::get).flatMap(Optional::stream)
                    .filter(r -> r.dimension().equals(dimension)).toList();
            project.addProperty("buildCount", records.size());
            if (p.id().equals(view.workshop().activeProject())) {
                value.addProperty("activeProject", p.id());
                for (BuildRecord r : records) {
                    ids.add(r.id()); var member = new com.google.gson.JsonObject(); member.addProperty("id", r.id());
                    member.addProperty("title", r.title()); member.addProperty("versions", r.current().number()); members.add(member);
                }
            }
            project.add("builds", ids); project.add("members", members); list.add(project);
        }
        value.add("projects", list);
        RouteReport report = view.workshop().route();
        if (report != null && Instant.now().isBefore(report.expires()) && report.dimension().equals(dimension)) {
            var route = new com.google.gson.JsonObject(); var lines = new com.google.gson.JsonArray(); report.result().lines().forEach(lines::add);
            route.add("lines", lines); route.addProperty("dimension", dimension.identifier().toString()); route.addProperty("remainingMillis", Math.max(0, Duration.between(Instant.now(), report.expires()).toMillis()));
            var markers = new com.google.gson.JsonArray();
            report.result().markers().forEach(p -> { var pos = new com.google.gson.JsonArray(); pos.add(p.getX()); pos.add(p.getY()); pos.add(p.getZ()); markers.add(pos); });
            route.add("markers", markers); value.add("route", route);
        }
        return value;
    }

}
