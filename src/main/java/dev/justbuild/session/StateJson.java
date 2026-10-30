package dev.justbuild.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.prompt.Guidance;
import dev.justbuild.world.BuildZone;
import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.DesignThumbnail;
import java.util.List;

/**
 * The panel's view of the server: settings, the choices for each, the profiles, and the player's recent builds
 * (newest first). API keys are never included; players who may edit profiles see whether one is saved.
 */
public final class StateJson {
    /** {@code elsewhere} names the build's dimension when the player is in a different one, otherwise null. */
    public record BuildSummary(String id, String title, int versions, String request, String elsewhere) {
        public BuildSummary(String id, String title, int versions, String request) {
            this(id, title, versions, request, null);
        }
    }

    public record DesignSummary(String id, String name, String folder, DesignThumbnail.Image thumbnail) {
        public DesignSummary(String id, String name) { this(id, name, "", DesignThumbnail.Image.EMPTY); }
    }

    public record LockSummary(String id, String name, BuildRegion region, boolean mine) { }
    public record PreviewSummary(String title, boolean movable, List<String> quality, List<String> options,
            int option, String position, int width, int height, int depth, dev.justbuild.world.ChangePreview.Counts counts,
            boolean revisable, int revisions, boolean previous) {
        public PreviewSummary(String title, boolean movable, List<String> quality, List<String> options,
                int option, String position) { this(title, movable, quality, options, option, position, 0, 0, 0, null, false, 0, false); }
    }
    public record Tools(BuildRegion part, boolean selectingPart, boolean partWand, List<LockSummary> locks,
            String connectionA, String connectionB, List<String> libraryFiles, PreviewSummary preview, String planningOptions,
            BuildRegion partOutline) {
        public Tools(BuildRegion part, boolean selectingPart, boolean partWand, List<LockSummary> locks,
                String connectionA, String connectionB, List<String> libraryFiles, PreviewSummary preview, String planningOptions) {
            this(part, selectingPart, partWand, locks, connectionA, connectionB, libraryFiles, preview, planningOptions, part);
        }
        public static final Tools EMPTY = new Tools(null, false, false, List.of(), null, null, List.of(), null, null);
    }

    public record Workspace(String selectedId, String selectedTitle, List<String> checkpoints,
            List<DesignSummary> designs, String retainedTitle, Tools tools) {
        public Workspace(String selectedId, String selectedTitle, List<String> checkpoints, List<DesignSummary> designs, String retainedTitle) {
            this(selectedId, selectedTitle, checkpoints, designs, retainedTitle, Tools.EMPTY);
        }
        public static final Workspace EMPTY = new Workspace(null, null, List.of(), List.of(), null, Tools.EMPTY);
    }

    /** Which AI plans this player's builds: "server" (label null = the active profile), "player", or "none". */
    public record Ai(String source, String label) {
        public static final Ai SERVER = new Ai("server", null);
        public static final Ai NONE = new Ai("none", "");
    }

    private StateJson() {
    }

    public static String build(JustBuildConfig config, List<BuildSummary> builds, boolean manage, Ai ai) {
        return build(config, builds, manage, ai, null, false);
    }

    public static String build(JustBuildConfig config, List<BuildSummary> builds, boolean manage, Ai ai,
            BuildZone zone, boolean selectingZone) {
        return build(config, builds, manage, ai, zone, selectingZone, Workspace.EMPTY);
    }

    public static String build(JustBuildConfig config, List<BuildSummary> builds, boolean manage, Ai ai,
            BuildZone zone, boolean selectingZone, Workspace workspace) {
        JustBuildConfig.ModelProfile active = config.active();
        JsonObject o = new JsonObject();
        o.addProperty("provider", active.provider);
        o.addProperty("profile", active.name);
        o.addProperty("model", active.model);
        o.addProperty("detail", config.detail);
        o.addProperty("reviewer", config.reviewerProfile == null || config.reviewerProfile.isBlank() ? "same" : config.reviewerProfile);
        o.addProperty("style", config.style);
        o.addProperty("manage", manage);
        o.addProperty("selectingZone", selectingZone);
        if (workspace.selectedId() != null) {
            o.addProperty("selectedBuild", workspace.selectedId());
            o.addProperty("selectedTitle", workspace.selectedTitle());
        }
        if (workspace.retainedTitle() != null) o.addProperty("retainedPlan", workspace.retainedTitle());
        o.add("checkpoints", strings(workspace.checkpoints()));
        JsonArray designs = new JsonArray();
        for (DesignSummary d : workspace.designs()) {
            JsonObject design = new JsonObject();
            design.addProperty("id", d.id());
            design.addProperty("name", d.name());
            design.addProperty("folder", d.folder());
            design.add("thumbnail", d.thumbnail().toJson());
            designs.add(design);
        }
        o.add("designs", designs);
        Tools tools = workspace.tools();
        if (tools.part() != null) {
            o.add("part", tools.part().toJson());
            o.addProperty("partLabel", tools.part().description());
        }
        o.addProperty("selectingPart", tools.selectingPart());
        if (tools.partOutline() != null) o.add("partOutline", tools.partOutline().toJson());
        o.addProperty("partWand", tools.partWand());
        JsonArray locks = new JsonArray();
        for (LockSummary lock : tools.locks()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", lock.id()); value.addProperty("name", lock.name());
            value.addProperty("mine", lock.mine()); value.add("region", lock.region().toJson()); locks.add(value);
        }
        o.add("lockedAreas", locks);
        if (tools.connectionA() != null) o.addProperty("connectionA", tools.connectionA());
        if (tools.connectionB() != null) o.addProperty("connectionB", tools.connectionB());
        o.add("libraryFiles", strings(tools.libraryFiles()));
        if (tools.planningOptions() != null) o.addProperty("planningOptions", tools.planningOptions());
        if (tools.preview() != null) {
            PreviewSummary preview = tools.preview();
            JsonObject value = new JsonObject();
            value.addProperty("title", preview.title()); value.addProperty("movable", preview.movable());
            value.add("quality", strings(preview.quality())); value.add("options", strings(preview.options()));
            value.addProperty("option", preview.option()); value.addProperty("position", preview.position());
            // Older games ignore these; a panel that never sees "revisable" never offers Revise.
            value.addProperty("revisable", preview.revisable()); value.addProperty("revisions", preview.revisions());
            value.addProperty("previous", preview.previous());
            if (preview.counts() != null) {
                value.addProperty("width", preview.width()); value.addProperty("height", preview.height()); value.addProperty("depth", preview.depth());
                value.addProperty("added", preview.counts().added()); value.addProperty("replaced", preview.counts().replaced());
                value.addProperty("removed", preview.counts().removed());
            }
            o.add("preview", value);
        }
        if (zone != null) {
            o.add("zone", zone.toJson());
            o.addProperty("zoneLabel", zone.description());
        }
        o.addProperty("playerAi", config.playerAi);
        o.addProperty("aiSource", ai.source());
        o.addProperty("aiLabel", ai.label() != null ? ai.label() : active.name);
        o.add("profileNames", strings(config.profileNames()));
        List<String> reviewers = new java.util.ArrayList<>(List.of("same"));
        reviewers.addAll(config.reviewerChoices());
        o.add("reviewerChoices", strings(reviewers));
        JsonArray profiles = new JsonArray();
        for (JustBuildConfig.ModelProfile p : config.profiles) {
            JsonObject e = new JsonObject();
            e.addProperty("name", p.name);
            e.addProperty("provider", p.provider);
            e.addProperty("model", p.model);
            if (manage) {
                if (p.isApi()) {
                    e.addProperty("baseUrl", p.baseUrl);
                    if (p.isOpenAi()) e.addProperty("reasoningEffort", p.reasoningEffort);
                    if (p.isAnthropic()) e.addProperty("maxTokens", p.outputTokens());
                }
                e.addProperty("hasKey", p.hasKey());
            }
            profiles.add(e);
        }
        o.add("profiles", profiles);
        o.add("details", strings(java.util.Arrays.stream(Guidance.DetailLevel.values()).map(Guidance.DetailLevel::id).toList()));
        o.add("styles", strings(java.util.Arrays.stream(Guidance.Style.values()).map(Guidance.Style::id).toList()));
        JsonArray list = new JsonArray();
        for (BuildSummary b : builds) {
            JsonObject e = new JsonObject();
            e.addProperty("id", b.id());
            e.addProperty("title", b.title());
            e.addProperty("versions", b.versions());
            e.addProperty("request", b.request());
            if (b.elsewhere() != null) e.addProperty("elsewhere", b.elsewhere());
            list.add(e);
        }
        o.add("builds", list);
        if (!builds.isEmpty()) {
            o.addProperty("latest", builds.get(0).id());
        }
        return o.toString();
    }

    private static JsonArray strings(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }
}
