package dev.justbuild.session;

import com.google.gson.*;
import dev.justbuild.plan.*;
import dev.justbuild.prompt.BuildConstraints;
import dev.justbuild.world.BuildRegion;
import dev.justbuild.world.BuildZone;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** One recoverable preview set per actor, separate from placed builds and undo history. */
public final class PreviewStore {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private final Path directory;
    public PreviewStore(Path directory) { this.directory = directory; }

    public record Choice(BuildPlan plan, String request, String typedRequest, ResourceKey<Level> dimension,
            BlockPos origin, Direction facing, String target, String version, BuildZone zone, BuildRegion focus,
            List<BuildRegion> guards, BuildConstraints constraints, String project, BuildPlan localChanges) {
        public Choice {
            Objects.requireNonNull(plan); Objects.requireNonNull(request); Objects.requireNonNull(dimension);
            Objects.requireNonNull(origin); Objects.requireNonNull(facing); Objects.requireNonNull(constraints);
            origin = origin.immutable(); guards = List.copyOf(guards);
            if (facing.getAxis().isVertical() || Math.abs((long) origin.getX()) > 30_000_000
                    || Math.abs((long) origin.getZ()) > 30_000_000 || Math.abs((long) origin.getY()) > 4096
                    || guards.size() > 32 || request.length() > 65536 || (target == null) != (version == null)
                    || target != null && (!target.matches("[0-9a-f]{8}") || version.isBlank())
                    || localChanges != null && target == null
                    || zone != null && !zone.dimension().equals(dimension)
                    || focus != null && !focus.dimension().equals(dimension)
                    || guards.stream().anyMatch(g -> !g.dimension().equals(dimension)))
                throw new IllegalArgumentException("Invalid saved preview frame");
        }
    }
    public record Saved(List<Choice> choices, int selected) {
        public Saved {
            choices = List.copyOf(choices);
            if (choices.isEmpty() || choices.size() > 3 || selected < 0 || selected >= choices.size())
                throw new IllegalArgumentException("Invalid saved preview choices");
        }
        public String title() { return choices.get(selected).plan().title(); }
    }
    private Path file(String actor) {
        if (!actor.equals("console") && !UUID.fromString(actor).toString().equals(actor))
            throw new IllegalArgumentException("Invalid preview owner");
        return directory.resolve(actor + ".json.gz");
    }
    public Saved load(String actor) throws IOException {
        Path path = file(actor);
        if (Files.notExists(path)) return null;
        try (var in = new GZIPInputStream(Files.newInputStream(path))) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Saved preview is too large");
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("format").getAsBigDecimal().intValueExact() != 1 || !actor.equals(root.get("owner").getAsString()))
                throw new IOException("Unsupported saved preview format or owner");
            var array = root.getAsJsonArray("choices");
            if (array.size() > 3) throw new IOException("Too many saved alternatives");
            List<Choice> choices = new ArrayList<>();
            for (var element : array) choices.add(readChoice(element.getAsJsonObject()));
            return new Saved(choices, root.get("selected").getAsBigDecimal().intValueExact());
        } catch (RuntimeException | PlanException e) { throw new IOException("Invalid saved preview", e); }
    }
    public void save(String actor, Saved saved) throws IOException {
        Path path = file(actor);
        // Never silently replace a corrupt/unsupported file. Explicit cancellation can remove it.
        load(actor);
        JsonObject root = new JsonObject(); root.addProperty("format", 1); root.addProperty("owner", actor);
        root.addProperty("selected", saved.selected());
        JsonArray choices = new JsonArray(); saved.choices().forEach(c -> choices.add(writeChoice(c))); root.add("choices", choices);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Saved preview is too large");
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, "preview-", ".tmp");
        try {
            try (var out = new GZIPOutputStream(Files.newOutputStream(temp))) { out.write(bytes); }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    public void delete(String actor) throws IOException { Files.deleteIfExists(file(actor)); }
    private static JsonObject writeChoice(Choice c) {
        JsonObject o = new JsonObject(); o.add("plan", PlanWriter.toJson(c.plan())); o.addProperty("request", c.request());
        if (c.typedRequest() != null) o.addProperty("typedRequest", c.typedRequest());
        o.addProperty("dimension", c.dimension().identifier().toString());
        JsonArray pos = new JsonArray(); pos.add(c.origin().getX()); pos.add(c.origin().getY()); pos.add(c.origin().getZ()); o.add("origin", pos);
        o.addProperty("facing", c.facing().getName());
        if (c.target() != null) { o.addProperty("target", c.target()); o.addProperty("version", c.version()); }
        if (c.zone() != null) o.add("zone", c.zone().toJson());
        if (c.focus() != null) o.add("focus", c.focus().toJson());
        JsonArray guards = new JsonArray(); c.guards().forEach(g -> guards.add(g.toJson())); o.add("guards", guards);
        o.add("constraints", c.constraints().toJson());
        if (c.project() != null) o.addProperty("project", c.project());
        if (c.localChanges() != null) o.add("localChanges", PlanWriter.toJson(c.localChanges()));
        return o;
    }
    private static Choice readChoice(JsonObject o) throws PlanException {
        JsonArray p = o.getAsJsonArray("origin");
        if (p.size() != 3 || o.getAsJsonArray("guards").size() > 32) throw new IllegalArgumentException();
        List<BuildRegion> guards = o.getAsJsonArray("guards").asList().stream().map(e -> BuildRegion.parse(e.getAsJsonObject())).toList();
        return new Choice(plan(o, "plan"), o.get("request").getAsString(), optional(o, "typedRequest"),
                ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString())),
                new BlockPos(p.get(0).getAsBigDecimal().intValueExact(), p.get(1).getAsBigDecimal().intValueExact(), p.get(2).getAsBigDecimal().intValueExact()),
                Direction.byName(o.get("facing").getAsString()), optional(o, "target"), optional(o, "version"),
                o.has("zone") ? BuildZone.fromJson(o.getAsJsonObject("zone")) : null,
                o.has("focus") ? BuildRegion.parse(o.getAsJsonObject("focus")) : null, guards,
                BuildConstraints.parse(o.getAsJsonObject("constraints")), optional(o, "project"), o.has("localChanges") ? plan(o, "localChanges") : null);
    }
    private static BuildPlan plan(JsonObject o, String key) throws PlanException {
        if (PlanParser.parse(o.getAsJsonObject(key).toString()) instanceof PlanResponse.Build build) return build.plan();
        throw new PlanException("Saved preview is not a build plan");
    }
    private static String optional(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsString() : null; }
}
