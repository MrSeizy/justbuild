package dev.justbuild.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanLimits;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.plan.PlanResponse;
import dev.justbuild.plan.PlanSize;
import dev.justbuild.plan.PlanViews;
import dev.justbuild.plan.Rasterizer;
import dev.justbuild.prompt.BuildContext;
import dev.justbuild.prompt.PlanningFeedback;
import dev.justbuild.prompt.BuildArea;
import dev.justbuild.prompt.BuildConstraints;
import dev.justbuild.prompt.PlanningAreas;
import dev.justbuild.prompt.ProjectTheme;
import dev.justbuild.prompt.WorldSnapshot;
import dev.justbuild.plan.InspectionRequest;
import dev.justbuild.prompt.EditContext;
import dev.justbuild.prompt.Exchange;
import dev.justbuild.prompt.GuideLibrary;
import dev.justbuild.prompt.Guidance;
import dev.justbuild.prompt.PromptBuilder;
import dev.justbuild.prompt.TerrainSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a request to the AI is made from, as data rather than text. The server builds one for every AI call and
 * turns it into text with {@link #userContent()}; a player's game that plans for a server receives the spec instead
 * of text, checks it (PromptCheck), and makes the same text itself, so a server can't hand the player's AI other
 * instructions. Placement, style, review views, and the shrink count are all derived here, never sent as text.
 * Guides travel as ids ({@code offerGuides}: the system prompt lists the menu; {@code guides}: the ones the AI read,
 * whose text comes from the game's own copy). {@code proposal} marks an edit whose plan is a waiting preview nobody
 * has placed (a revision).
 */
public record PromptSpec(Kind kind, String dimension, String biome, Placement placement, String style,
        String direction, String request, List<Exchange> exchanges, int remaining, TerrainSummary terrain,
        String originalRequest, List<String> earlierChanges, String planJson, int round, int rounds, int maxBlocks,
        boolean offerGuides, List<String> guides, BuildArea zone, List<WorldSnapshot> inspections, int inspectionRemaining,
        PlanningAreas areas, ProjectTheme theme, BuildConstraints constraints, PlanningFeedback feedback, boolean proposal) {

    public enum Kind { NEW, EDIT, REVIEW, SHRINK }

    /** Where (0, 0, 0) is: in front of the player ({@link #FRONT}), or at a spot they pointed at {@code distance} away. */
    public record Placement(int distance, int x, int y, int z) {
        public static final Placement FRONT = new Placement(0, 0, 0, 0);

        String text() {
            return distance <= 0 ? PromptBuilder.frontPlacement()
                    : PromptBuilder.targetPlacement(distance, new Cell(x, y, z));
        }
    }

    public static final class SpecException extends Exception {
        public SpecException(String message) {
            super(message);
        }
    }

    public PromptSpec {
        exchanges = List.copyOf(exchanges);
        earlierChanges = List.copyOf(earlierChanges);
        guides = List.copyOf(guides);
        inspections = List.copyOf(inspections);
        areas = areas == null ? PlanningAreas.EMPTY : areas;
        theme = theme == null ? ProjectTheme.EMPTY : theme;
        constraints = constraints == null ? BuildConstraints.NONE : constraints;
        feedback = feedback == null ? PlanningFeedback.EMPTY : feedback;
    }

    /** Every request except a revision of an unplaced preview. */
    public PromptSpec(Kind kind, String dimension, String biome, Placement placement, String style,
            String direction, String request, List<Exchange> exchanges, int remaining, TerrainSummary terrain,
            String originalRequest, List<String> earlierChanges, String planJson, int round, int rounds, int maxBlocks,
            boolean offerGuides, List<String> guides, BuildArea zone, List<WorldSnapshot> inspections, int inspectionRemaining,
            PlanningAreas areas, ProjectTheme theme, BuildConstraints constraints, PlanningFeedback feedback) {
        this(kind, dimension, biome, placement, style, direction, request, exchanges, remaining, terrain,
                originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offerGuides, guides, zone,
                inspections, inspectionRemaining, areas, theme, constraints, feedback, false);
    }

    public PromptSpec(Kind kind, String dimension, String biome, Placement placement, String style,
            String direction, String request, List<Exchange> exchanges, int remaining, TerrainSummary terrain,
            String originalRequest, List<String> earlierChanges, String planJson, int round, int rounds, int maxBlocks,
            boolean offerGuides, List<String> guides, BuildArea zone, List<WorldSnapshot> inspections, int inspectionRemaining,
            PlanningAreas areas, ProjectTheme theme, BuildConstraints constraints) {
        this(kind, dimension, biome, placement, style, direction, request, exchanges, remaining, terrain,
                originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offerGuides, guides, zone,
                inspections, inspectionRemaining, areas, theme, constraints, PlanningFeedback.EMPTY);
    }

    public PromptSpec(Kind kind, String dimension, String biome, Placement placement, String style,
            String direction, String request, List<Exchange> exchanges, int remaining, TerrainSummary terrain,
            String originalRequest, List<String> earlierChanges, String planJson, int round, int rounds, int maxBlocks,
            boolean offerGuides, List<String> guides, BuildArea zone, List<WorldSnapshot> inspections, int inspectionRemaining,
            PlanningAreas areas, ProjectTheme theme) {
        this(kind, dimension, biome, placement, style, direction, request, exchanges, remaining, terrain,
                originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offerGuides, guides, zone,
                inspections, inspectionRemaining, areas, theme, BuildConstraints.NONE);
    }

    public PromptSpec(Kind kind, String dimension, String biome, Placement placement, String style,
            String direction, String request, List<Exchange> exchanges, int remaining, TerrainSummary terrain,
            String originalRequest, List<String> earlierChanges, String planJson, int round, int rounds, int maxBlocks,
            boolean offerGuides, List<String> guides, BuildArea zone, List<WorldSnapshot> inspections, int inspectionRemaining,
            PlanningAreas areas) {
        this(kind, dimension, biome, placement, style, direction, request, exchanges, remaining, terrain,
                originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offerGuides, guides, zone,
                inspections, inspectionRemaining, areas, ProjectTheme.EMPTY);
    }

    public PromptSpec(Kind kind, String dimension, String biome, Placement placement, String style,
            String direction, String request, List<Exchange> exchanges, int remaining, TerrainSummary terrain,
            String originalRequest, List<String> earlierChanges, String planJson, int round, int rounds, int maxBlocks,
            boolean offerGuides, List<String> guides, BuildArea zone, List<WorldSnapshot> inspections, int inspectionRemaining) {
        this(kind, dimension, biome, placement, style, direction, request, exchanges, remaining, terrain,
                originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offerGuides, guides, zone,
                inspections, inspectionRemaining, PlanningAreas.EMPTY);
    }

    /** Compatibility constructor for callers that do not attach world context. */
    public PromptSpec(Kind kind, String dimension, String biome, Placement placement, String style,
            String direction, String request, List<Exchange> exchanges, int remaining, TerrainSummary terrain,
            String originalRequest, List<String> earlierChanges, String planJson, int round, int rounds, int maxBlocks,
            boolean offerGuides, List<String> guides) {
        this(kind, dimension, biome, placement, style, direction, request, exchanges, remaining, terrain,
                originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offerGuides, guides,
                null, List.of(), 0);
    }

    public static PromptSpec newBuild(String dimension, String biome, Placement placement, String style,
            String direction, String request, List<Exchange> exchanges, int remaining, TerrainSummary terrain,
            int maxBlocks) {
        return new PromptSpec(Kind.NEW, dimension, biome, placement, style, direction, request, exchanges, remaining,
                terrain, null, List.of(), null, 0, 0, maxBlocks, false, List.of());
    }

    /** {@code planJson} is the build's current plan; {@code change} is what the player asked for. */
    public static PromptSpec edit(String dimension, String biome, String originalRequest, List<String> earlierChanges,
            String planJson, String change, List<Exchange> exchanges, int remaining, int maxBlocks) {
        return new PromptSpec(Kind.EDIT, dimension, biome, null, null, null, change, exchanges, remaining, null,
                originalRequest, earlierChanges, planJson, 0, 0, maxBlocks, false, List.of());
    }

    /**
     * A revision of a waiting preview nobody has placed: {@code planJson} is that preview's plan and {@code change}
     * what the player asked for. It keeps the player's style; terrain describes the site.
     */
    public static PromptSpec revision(String dimension, String biome, String style, String originalRequest,
            List<String> earlierChanges, String planJson, String change, List<Exchange> exchanges, int remaining,
            TerrainSummary terrain, int maxBlocks) {
        return new PromptSpec(Kind.EDIT, dimension, biome, null, style, null, change, exchanges, remaining, terrain,
                originalRequest, earlierChanges, planJson, 0, 0, maxBlocks, false, List.of(), null, List.of(), 0,
                PlanningAreas.EMPTY, ProjectTheme.EMPTY, BuildConstraints.NONE, PlanningFeedback.EMPTY, true);
    }

    /** A self-review of a complete candidate, retaining the new-build or edit context and guides. */
    public PromptSpec review(String plan, int reviewRound, int reviewRounds) {
        return new PromptSpec(Kind.REVIEW, dimension, biome, placement, style, direction, request, exchanges, 0,
                terrain, originalRequest, earlierChanges, plan, reviewRound, reviewRounds, maxBlocks, false, guides, zone, inspections, 0, areas, theme, constraints, feedback, proposal);
    }

    /** A request for a smaller version of {@code planJson}, made from this new-build or edit spec (no guides). */
    public PromptSpec shrink(String plan) {
        return new PromptSpec(Kind.SHRINK, dimension, biome, placement, style, direction, request, exchanges, 0,
                terrain, originalRequest, earlierChanges, plan, 0, 0, maxBlocks, false, List.of(), zone, inspections, 0, areas, theme, constraints, feedback, proposal);
    }

    /** The same spec offering the guide menu or carrying the guides read (ids from prompt.GuideLibrary). */
    public PromptSpec withGuides(boolean offer, List<String> read) {
        return new PromptSpec(kind, dimension, biome, placement, style, direction, request, exchanges, remaining,
                terrain, originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offer, read,
                zone, inspections, inspectionRemaining, areas, theme, constraints, feedback, proposal);
    }

    /** The same spec with the plan replaced (PromptCheck uses it to put in a re-written copy). */
    PromptSpec withPlan(String plan) {
        return new PromptSpec(kind, dimension, biome, placement, style, direction, request, exchanges, remaining,
                terrain, originalRequest, earlierChanges, plan, round, rounds, maxBlocks, offerGuides, guides,
                zone, inspections, inspectionRemaining, areas, theme, constraints, feedback, proposal);
    }

    public PromptSpec withFeedback(PlanningFeedback value) {
        return new PromptSpec(kind, dimension, biome, placement, style, direction, request, exchanges, remaining,
                terrain, originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offerGuides, guides,
                zone, inspections, inspectionRemaining, areas, theme, constraints, value, proposal);
    }

    public PromptSpec withWorld(BuildArea area, List<WorldSnapshot> snapshots, int readsLeft) {
        return new PromptSpec(kind, dimension, biome, placement, style, direction, request, exchanges, remaining,
                terrain, originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offerGuides, guides,
                area, snapshots, readsLeft, areas, theme, constraints, feedback, proposal);
    }

    public PromptSpec withAreas(PlanningAreas value) {
        return new PromptSpec(kind, dimension, biome, placement, style, direction, request, exchanges, remaining,
                terrain, originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offerGuides, guides,
                zone, inspections, inspectionRemaining, value, theme, constraints, feedback, proposal);
    }

    public PromptSpec withTheme(ProjectTheme value) {
        return new PromptSpec(kind, dimension, biome, placement, style, direction, request, exchanges, remaining,
                terrain, originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offerGuides, guides,
                zone, inspections, inspectionRemaining, areas, value, constraints, feedback, proposal);
    }

    public PromptSpec withConstraints(BuildConstraints value) {
        return new PromptSpec(kind, dimension, biome, placement, style, direction, request, exchanges, remaining,
                terrain, originalRequest, earlierChanges, planJson, round, rounds, maxBlocks, offerGuides, guides,
                zone, inspections, inspectionRemaining, areas, theme, value, feedback, proposal);
    }

    /** The system prompt for this request, made the same way on the server and in a player's game. */
    public String systemPrompt(PlanLimits limits, Guidance.DetailLevel detail) {
        return PromptBuilder.systemPrompt(limits, detail, offerGuides, inspectionRemaining);
    }

    BuildContext context() {
        // Auto leaves style interpretation to the AI; only an explicit setting supplies a preset.
        Guidance.Style chosen = style == null ? null : Guidance.Style.parse(style).orElse(null);
        String styleLine = chosen == null ? null : chosen.promptLine();
        String location = placement == null ? null : zone == null ? placement.text()
                : "The origin (0, 0, 0) is at the center of the selected footprint, at the ground surface. "
                        + "Center the build here and fit it inside the attached zone coordinates.";
        return new BuildContext(dimension, biome, location, styleLine, direction);
    }

    /** The request text for the AI, made the same way on the server and in a player's game. */
    public String userContent() throws PlanException {
        String text = switch (kind) {
            case NEW -> PromptBuilder.userContent(context(), request, exchanges, remaining, terrain);
            case EDIT -> proposal
                    ? PromptBuilder.revisionContent(context(),
                            new EditContext(originalRequest, earlierChanges, planJson, request), exchanges, remaining, terrain)
                    : PromptBuilder.editContent(context(),
                            new EditContext(originalRequest, earlierChanges, planJson, request), exchanges, remaining);
            case REVIEW -> PromptBuilder.reviewContent(context(), request, originalRequest, earlierChanges, exchanges, planJson,
                    reviewViews(), round, rounds, proposal);
            case SHRINK -> {
                int cap = (int) Math.min(4L * maxBlocks, 1_000_000L);
                yield PromptBuilder.shrinkContent(context(), request, originalRequest, earlierChanges, exchanges, planJson,
                        PlanSize.describe(PlanSize.blocks(plan(), cap), cap), maxBlocks, proposal);
            }
        };
        if (terrain != null && (kind == Kind.REVIEW || kind == Kind.SHRINK)) text += "\n\n" + terrain.format();
        if (zone != null) {
            text += "\n\n" + zone.format();
        }
        text += areas.format();
        text += theme.format();
        text += constraints.format();
        for (WorldSnapshot snapshot : inspections) {
            text += "\n\n" + snapshot.format();
        }
        if (!inspections.isEmpty()) {
            text += "\nUse these live samples to guide changes; the saved plan describes intent and may differ from "
                    + "the world. Preserve unrelated work. Later overlapping samples supersede earlier samples.";
        }
        text += feedback.format();
        return PromptBuilder.withGuides(text, GuideLibrary.defaults().resolve(guides));
    }

    /** A rejected draft can still receive repair guidance without trying to draw unsafe geometry. */
    private String reviewViews() {
        String unavailable = "Text views unavailable: the draft exceeds safe rendering limits. Correct the reported diagnostics and return a bounded plan.";
        if (feedback.problems().stream().anyMatch(p -> p.kind() == PlanningFeedback.Kind.LIMIT)) return unavailable;
        try {
            BuildPlan draft = plan();
            new PlanLimits(PromptCheck.MAX_OPERATIONS, maxBlocks, PromptCheck.MAX_SIZE).check(draft);
            return PlanViews.render(Rasterizer.rasterize(draft.operations(), maxBlocks, PlanSize.FLAT_GROUND, draft.texture()));
        } catch (PlanException invalid) {
            return unavailable;
        }
    }

    /** The plan this spec carries, parsed. */
    BuildPlan plan() throws PlanException {
        if (planJson == null || !(PlanParser.parse(planJson) instanceof PlanResponse.Build build)) {
            throw new PlanException("the request has no build plan");
        }
        return build.plan();
    }

    // ---- JSON ----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind.name());
        o.addProperty("dimension", dimension);
        o.addProperty("biome", biome);
        if (placement != null) {
            JsonObject p = new JsonObject();
            p.addProperty("distance", placement.distance());
            p.addProperty("x", placement.x());
            p.addProperty("y", placement.y());
            p.addProperty("z", placement.z());
            o.add("placement", p);
        }
        o.addProperty("style", style);
        o.addProperty("direction", direction);
        o.addProperty("request", request);
        JsonArray ex = new JsonArray();
        for (Exchange e : exchanges) {
            JsonObject x = new JsonObject();
            x.addProperty("question", e.question());
            x.addProperty("answer", e.answer());
            ex.add(x);
        }
        o.add("exchanges", ex);
        o.addProperty("remaining", remaining);
        if (terrain != null) {
            JsonObject t = new JsonObject();
            t.addProperty("step", terrain.step());
            t.addProperty("minX", terrain.minX());
            t.addProperty("minZ", terrain.minZ());
            JsonArray rows = new JsonArray();
            for (int[] row : terrain.heights()) {
                JsonArray r = new JsonArray();
                for (int h : row) {
                    if (h == TerrainSummary.UNKNOWN) {
                        r.add(JsonNull.INSTANCE);
                    } else {
                        r.add(h);
                    }
                }
                rows.add(r);
            }
            t.add("heights", rows);
            JsonObject surface = new JsonObject();
            terrain.surface().forEach(surface::addProperty);
            t.add("surface", surface);
            o.add("terrain", t);
        }
        o.addProperty("originalRequest", originalRequest);
        JsonArray earlier = new JsonArray();
        earlierChanges.forEach(earlier::add);
        o.add("earlierChanges", earlier);
        o.addProperty("plan", planJson);
        o.addProperty("round", round);
        o.addProperty("rounds", rounds);
        o.addProperty("maxBlocks", maxBlocks);
        o.addProperty("offerGuides", offerGuides);
        JsonArray read = new JsonArray();
        guides.forEach(read::add);
        o.add("guides", read);
        if (zone != null) {
            o.add("zone", zone.toJson());
        }
        JsonArray snapshots = new JsonArray();
        inspections.forEach(s -> snapshots.add(s.toJson()));
        o.add("inspections", snapshots);
        o.addProperty("inspectionRemaining", inspectionRemaining);
        o.add("areas", areas.toJson());
        o.add("theme", theme.toJson());
        o.add("constraints", constraints.toJson());
        o.add("feedback", feedback.toJson());
        // Written only when set, so ordinary requests stay byte-for-byte what 0.2.0 games read.
        if (proposal) o.addProperty("proposal", true);
        return o;
    }

    public static PromptSpec parse(String json) throws SpecException {
        try {
            return parse(JsonParser.parseString(json).getAsJsonObject());
        } catch (IllegalStateException | com.google.gson.JsonParseException e) {
            throw new SpecException("Could not read the request: " + e.getMessage());
        }
    }

    public static PromptSpec parse(JsonObject o) throws SpecException {
        try {
            Kind kind = Kind.valueOf(string(o, "kind"));
            Placement placement = null;
            if (o.get("placement") instanceof JsonObject p) {
                placement = new Placement(p.get("distance").getAsInt(), p.get("x").getAsInt(), p.get("y").getAsInt(),
                        p.get("z").getAsInt());
            }
            List<Exchange> exchanges = new ArrayList<>();
            for (JsonElement e : array(o, "exchanges")) {
                JsonObject x = e.getAsJsonObject();
                exchanges.add(new Exchange(string(x, "question"), string(x, "answer")));
            }
            TerrainSummary terrain = null;
            if (o.get("terrain") instanceof JsonObject t) {
                JsonArray rows = t.getAsJsonArray("heights");
                int[][] heights = new int[rows.size()][];
                for (int r = 0; r < rows.size(); r++) {
                    JsonArray row = rows.get(r).getAsJsonArray();
                    heights[r] = new int[row.size()];
                    for (int c = 0; c < row.size(); c++) {
                        heights[r][c] = row.get(c).isJsonNull() ? TerrainSummary.UNKNOWN : row.get(c).getAsInt();
                    }
                }
                Map<String, Integer> surface = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> s : t.getAsJsonObject("surface").entrySet()) {
                    surface.put(s.getKey(), s.getValue().getAsInt());
                }
                terrain = new TerrainSummary(t.get("step").getAsInt(), t.get("minX").getAsInt(),
                        t.get("minZ").getAsInt(), heights, surface);
            }
            List<String> earlier = new ArrayList<>();
            for (JsonElement e : array(o, "earlierChanges")) {
                earlier.add(e.getAsString());
            }
            List<String> guides = new ArrayList<>();
            for (JsonElement e : array(o, "guides")) {
                guides.add(e.getAsString());
            }
            BuildArea zone = o.get("zone") instanceof JsonObject area ? BuildArea.parse(area) : null;
            List<WorldSnapshot> inspections = new ArrayList<>();
            JsonArray snapshots = array(o, "inspections");
            if (snapshots.size() > InspectionRequest.MAX_ROUNDS + 1) {
                throw new IllegalArgumentException("too many inspections");
            }
            for (JsonElement snapshot : snapshots) {
                inspections.add(WorldSnapshot.parse(snapshot.getAsJsonObject()));
            }
            return new PromptSpec(kind, string(o, "dimension"), string(o, "biome"), placement, string(o, "style"),
                    string(o, "direction"), string(o, "request"), exchanges, o.get("remaining").getAsInt(), terrain,
                    string(o, "originalRequest"), earlier, string(o, "plan"), o.get("round").getAsInt(),
                    o.get("rounds").getAsInt(), o.get("maxBlocks").getAsInt(),
                    o.has("offerGuides") && o.get("offerGuides").getAsBoolean(), guides, zone, inspections,
                    o.has("inspectionRemaining") ? o.get("inspectionRemaining").getAsBigDecimal().intValueExact() : 0,
                    o.get("areas") instanceof JsonObject areas ? PlanningAreas.parse(areas) : PlanningAreas.EMPTY,
                    o.get("theme") instanceof JsonObject theme ? ProjectTheme.parse(theme) : ProjectTheme.EMPTY,
                    o.get("constraints") instanceof JsonObject constraints ? BuildConstraints.parse(constraints) : BuildConstraints.NONE,
                    o.get("feedback") instanceof JsonObject feedback ? PlanningFeedback.parse(feedback) : PlanningFeedback.EMPTY,
                    o.has("proposal") && o.get("proposal").getAsBoolean());
        } catch (RuntimeException e) {
            throw new SpecException("Could not read the request: " + e);
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.get(key) instanceof JsonArray a ? a : new JsonArray();
    }
}
