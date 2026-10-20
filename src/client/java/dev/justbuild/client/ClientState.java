package dev.justbuild.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.DesignThumbnail;
import net.minecraft.network.chat.Component;

/** Immutable decoded server state plus transient chat/status, owned by the client thread. */
final class ClientState {
    record Build(String id, String title, int versions, String request, String elsewhere) {
        Build(String id, String title, int versions, String request) {
            this(id, title, versions, request, null);
        }
    }

    record Design(String id, String name, String folder, DesignThumbnail.Image thumbnail) { }

    record Locked(String id, String name, BuildRegion region, boolean mine) { }

    record Preview(String title, boolean movable, List<String> quality, List<String> options, int option, String position,
            int width, int height, int depth, int added, int replaced, int removed) {
        boolean hasCounts() { return added >= 0; }
    }

    record QualityIssue(String id, String message, String repair, String dimension, net.minecraft.core.BlockPos position) { }

    record Material(String id, int count) { }

    record Project(String id, String name, dev.justbuild.prompt.ProjectTheme theme, List<String> builds, int count, List<Build> members,
            boolean mine, String ownerName, List<Collaborator> team) { }

    record Collaborator(String id, String name) { }

    record Route(String dimension, long expires, List<String> lines, List<net.minecraft.core.BlockPos> markers) { }

    record PolygonDraft(String dimension, int y, List<dev.justbuild.prompt.Footprint.Point> points) { }

    record Version(String id, int number, String request, long createdAt, boolean current, DesignThumbnail.Image thumbnail) { }

    record Profile(String name, String provider, String model, String baseUrl, boolean hasKey, String reasoningEffort) {
    }

    record Snapshot(Map<String, String> strings, Map<String, List<String>> lists,
            List<QualityIssue> qualityIssues,
            dev.justbuild.provider.UsageTracker.Snapshot usage,
            dev.justbuild.world.BuildZone zone,
            dev.justbuild.prompt.BuildConstraints constraints,
            java.util.Map<String, BuildRegion> namedParts,
            List<Version> versions,
            PolygonDraft polygonDraft,
            String sampledMaterial,
            String activeProject,
            List<Material> materials,
            List<Project> projects,
            String planning,
            String question,
            List<String> questionOptions,
            String undoWaiting,
            String undoTarget,
            String placing,
            Route route,
            List<Design> designs,
            List<Locked> locks,
            BuildRegion selectedBounds,
            BuildRegion part,
            BuildRegion partOutline,
            Preview preview,
            List<Profile> profiles,
            List<Build> builds) {
        Snapshot withString(String key, String value) {
            var copy = new java.util.HashMap<>(strings);
            if (value == null) copy.remove(key); else copy.put(key, value);
            return new Snapshot(Map.copyOf(copy), lists,
                    qualityIssues, usage, zone, constraints, namedParts, versions, polygonDraft, sampledMaterial, activeProject, materials, projects, planning, question, questionOptions, undoWaiting, undoTarget, placing, route, designs, locks, selectedBounds, part, partOutline, preview, profiles, builds);
        }
    }
    private static final int MAX_LINES = 10;
    private static Snapshot state = ClientStateDecoder.decode("{}");
    private static final Deque<Component> lines = new ArrayDeque<>();
    private static long lineCount;
    private static String status = "";
    private static long statusAt;
    private ClientState() {}

    static void update(String json) {
        try {
            Snapshot next = ClientStateDecoder.decode(json);
            state = next;
            ZoneOutline.receive(next.zone() == null ? null : next.zone().toJson());
        } catch (RuntimeException ignored) { /* Keep the last good snapshot. */ }
    }
    static void clear() {
        state = ClientStateDecoder.decode("{}");
        lines.clear(); status = "";
        ZoneOutline.receive(null); SelectionHandles.clear(); SelectionLines.clear();
    }
    static boolean known() { return state.strings().containsKey("provider"); }
    static String string(String key, String fallback) { return state.strings().getOrDefault(key, fallback); }
    static boolean flag(String key) { return Boolean.parseBoolean(state.strings().get(key)); }
    static boolean manage() { return flag("manage"); }
    static List<String> list(String key) { return state.lists().getOrDefault(key, List.of()); }
    static void set(String key, String value) { state = state.withString(key, value); }
    static List<QualityIssue> qualityIssues() { return state.qualityIssues(); }
    static dev.justbuild.provider.UsageTracker.Snapshot usage() { return state.usage(); }
    static dev.justbuild.world.BuildZone zone() { return state.zone(); }
    static dev.justbuild.prompt.BuildConstraints constraints() { return state.constraints(); }
    static java.util.Map<String, BuildRegion> namedParts() { return state.namedParts(); }
    static List<Version> versions() { return state.versions(); }
    static PolygonDraft polygonDraft() { return state.polygonDraft(); }
    static String sampledMaterial() { return state.sampledMaterial(); }
    static String activeProject() { return state.activeProject(); }
    static List<Material> materials() { return state.materials(); }
    static List<Project> projects() { return state.projects(); }
    static String planning() { return state.planning(); }
    static String question() { return state.question(); }
    static List<String> questionOptions() { return state.questionOptions(); }
    static String undoWaiting() { return state.undoWaiting(); }
    static String undoTarget() { return state.undoTarget(); }
    static String placing() { return state.placing(); }
    static Route route() { Route route = state.route(); return route != null && route.expires() >= System.currentTimeMillis() ? route : null; }
    static List<Design> designs() { return state.designs(); }
    static List<Locked> locks() { return state.locks(); }
    static BuildRegion selectedBounds() { return state.selectedBounds(); }
    static BuildRegion part() { return state.part(); }
    static BuildRegion partOutline() { return state.partOutline(); }
    static Preview preview() { return state.preview(); }
    static List<Profile> profiles() { return state.profiles(); }
    static List<Build> builds() { return state.builds(); }
    static void addLine(Component line) {
        lines.addLast(line);
        lineCount++;
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }
    static List<Component> lines() {
        return List.copyOf(lines);
    }
    static long lineCount() {
        return lineCount;
    }
    static Component lastLineSince(long mark) {
        return lineCount > mark ? lines.peekLast() : null;
    }
    static void status(String text) {
        status = text;
        statusAt = System.currentTimeMillis();
    }
    static String status() {
        return System.currentTimeMillis() - statusAt < 5_000 ? status : "";
    }
}
