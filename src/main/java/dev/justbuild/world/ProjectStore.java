package dev.justbuild.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.justbuild.prompt.ProjectTheme;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Projects group independent build records; changing membership never changes blocks or their undo histories. A
 * project's owner can add collaborators (players, by UUID with their last known name): the owner and collaborators may
 * change every build in the project.
 */
public final class ProjectStore {
    public static final int MAX_PROJECTS = 128, MAX_MEMBERS = 128, MAX_COLLABORATORS = 16;
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    public record Project(String id, String owner, String name, ResourceKey<Level> dimension, ProjectTheme theme,
            List<String> builds, Map<String, String> collaborators) {
        public Project {
            if (id == null || !id.matches("[a-f0-9]{8,32}") || owner == null || owner.length() > 80
                    || dimension == null || theme == null) throw new IllegalArgumentException("Invalid project.");
            name = BuildRecord.label(name); builds = List.copyOf(builds);
            if (builds.size() > MAX_MEMBERS || builds.stream().distinct().count() != builds.size()
                    || builds.stream().anyMatch(b -> !b.matches("[a-f0-9]{8,32}"))) throw new IllegalArgumentException("Invalid project members.");
            collaborators = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(collaborators));
            if (collaborators.size() > MAX_COLLABORATORS || collaborators.containsKey(owner)
                    || collaborators.keySet().stream().anyMatch(c -> !c.matches(UUID))
                    || collaborators.values().stream().anyMatch(n -> n == null || n.isBlank() || n.length() > 32)) {
                throw new IllegalArgumentException("Invalid project collaborators.");
            }
        }
        public Project(String id, String owner, String name, ResourceKey<Level> dimension, ProjectTheme theme, List<String> builds) {
            this(id, owner, name, dimension, theme, builds, Map.of());
        }
        public Project withTheme(ProjectTheme value) { return new Project(id, owner, name, dimension, value, builds, collaborators); }
        public Project withBuilds(List<String> value) { return new Project(id, owner, name, dimension, theme, value, collaborators); }
        public Project withCollaborators(Map<String, String> value) { return new Project(id, owner, name, dimension, theme, builds, value); }
        /** The owner or a collaborator. */
        public boolean team(String actor) { return owner.equals(actor) || collaborators.containsKey(actor); }
    }

    /** Whether {@code actor} may change {@code record}: its owner, or the team of a project it belongs to. */
    public static boolean mayModify(String actor, BuildRecord record, java.util.Collection<Project> projects) {
        return record.owner().equals(actor)
                || projects.stream().anyMatch(p -> p.builds().contains(record.id()) && p.team(actor));
    }

    /** Sharing is an ownership decision: an editor cannot transfer somebody else's build to another team. */
    public static boolean mayChangeMembership(String actor, BuildRecord record, Project project, boolean adding) {
        return record.owner().equals(actor) || !adding && project.owner().equals(actor);
    }
    private final Path file;
    public ProjectStore(Path file) { this.file = file; }
    public List<Project> load() throws IOException {
        if (!Files.exists(file)) return List.of();
        if (Files.size(file) > 2_000_000) throw new IOException("Project file is too large.");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("format").getAsInt() != 1) throw new IllegalArgumentException("Unsupported project format.");
            JsonArray array = root.getAsJsonArray("projects");
            if (array.size() > MAX_PROJECTS) throw new IllegalArgumentException("Too many projects.");
            List<Project> result = new ArrayList<>();
            for (var e : array) {
                JsonObject p = e.getAsJsonObject(); JsonArray members = p.getAsJsonArray("builds");
                if (members.size() > MAX_MEMBERS) throw new IllegalArgumentException("Too many project members.");
                List<String> ids = new ArrayList<>(); members.forEach(m -> ids.add(m.getAsString()));
                Map<String, String> collaborators = new java.util.LinkedHashMap<>();
                if (p.get("collaborators") instanceof JsonArray list) {
                    for (var c : list) collaborators.put(c.getAsJsonObject().get("id").getAsString(), c.getAsJsonObject().get("name").getAsString());
                }
                result.add(new Project(p.get("id").getAsString(), p.get("owner").getAsString(), p.get("name").getAsString(),
                        ResourceKey.create(Registries.DIMENSION, Identifier.parse(p.get("dimension").getAsString())),
                        ProjectTheme.parse(p.getAsJsonObject("theme")), ids, collaborators));
            }
            if (result.stream().map(Project::id).distinct().count() != result.size()) throw new IllegalArgumentException("Duplicate project ids.");
            return List.copyOf(result);
        } catch (RuntimeException e) { throw new IOException("Could not read projects: " + e.getMessage(), e); }
    }
    public void save(List<Project> projects) throws IOException {
        if (projects.size() > MAX_PROJECTS) throw new IOException("Too many projects.");
        JsonObject root = new JsonObject(); root.addProperty("format", 1); JsonArray array = new JsonArray();
        for (Project p : projects) {
            JsonObject o = new JsonObject(); o.addProperty("id", p.id()); o.addProperty("owner", p.owner()); o.addProperty("name", p.name());
            o.addProperty("dimension", p.dimension().identifier().toString()); o.add("theme", p.theme().toJson());
            JsonArray ids = new JsonArray(); p.builds().forEach(ids::add); o.add("builds", ids);
            JsonArray team = new JsonArray();
            p.collaborators().forEach((id, name) -> { JsonObject c = new JsonObject(); c.addProperty("id", id); c.addProperty("name", name); team.add(c); });
            o.add("collaborators", team); array.add(o);
        }
        root.add("projects", array); Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp"); Files.writeString(temp, root.toString());
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
