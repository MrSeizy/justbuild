package dev.justbuild.relay;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.CritiqueNote;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.InspectionRequest;
import dev.justbuild.plan.PlanLimits;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.plan.PlanResponse;
import dev.justbuild.plan.PlanSanitizer;
import dev.justbuild.plan.PlanWriter;
import dev.justbuild.plan.RepairOutcome;
import dev.justbuild.prompt.Exchange;
import dev.justbuild.prompt.GuideLibrary;
import dev.justbuild.prompt.Guidance;
import dev.justbuild.prompt.TerrainSummary;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The player's game checks a server's request before its AI sees it. Every field is either something this game saw
 * the player type or its own AI say, a known id, a number, or a plan; anything else is refused. A review, critique or
 * shrink must carry a plan this game's own AI returned, and any critique it carries must be one this game's AI
 * returned; an edit's plan (the server's saved build) reaches the AI as block ids and shape words only. What is left
 * for a server to steer: the build-history texts of an edit, capped in length and number (a critique made in an edit
 * context carries the same bounded history into a later repair of that plan).
 */
public final class PromptCheck {
    /**
     * A request the player started in chat or the bounded editor, now or earlier on this computer (what
     * /justbuild again reruns). Matching what the player typed is the real check; this only bounds size.
     */
    static final int MAX_TEXT = 8192; // 4096 typed characters plus bounded server-generated alternative instructions.
    /** Build-history texts (an edit's original request and earlier changes) and how many of them. */
    public static final int MAX_HISTORY_TEXT = 600;
    public static final int MAX_HISTORY = 10;
    static final int MAX_TERRAIN = 64;
    static final int MAX_SURFACE = 32;
    static final int MAX_PLAN = 1_500_000;
    private static final Pattern ID = Pattern.compile("([a-z0-9_.-]{1,40}:)?[a-z0-9_./-]{1,80}");
    /** Modded dimension and biome ids can be long; previews carry up to 256 characters of dimension. */
    private static final Pattern WORLD_ID = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");
    static final int MAX_WORLD_ID = 256;
    /** Sanity bounds checked before this game draws a server's plan (the server's own limits are tighter). */
    static final int MAX_OPERATIONS = 10_000;
    static final int MAX_SIZE = 1024;

    /** Identifies a plan by its re-written form, so what this game's AI returned and the server's echo of it match. */
    public static String planKey(String planJson) {
        try {
            if (!(PlanParser.parse(planJson) instanceof PlanResponse.Build build)) return "";
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(PlanWriter.write(build.plan()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (PlanException e) {
            return "";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A block id this game can place (renamed and singular ids included), as opposed to text in a block field. */
    public static boolean knownBlock(String spec) {
        return dev.justbuild.world.BlockResolver.parse(spec).isPresent();
    }

    /**
     * What this game has seen: the player's AI-starting requests, its own AI's questions, the player's answers, and the
     * {@link #planKey keys} of the plans and the critique keys its AI returned, and the repairs it ran: each
     * {@code critiqueKey + ">" + planKey} pair says a repair carrying that critique returned that plan.
     */
    public record Seen(Set<String> requests, Set<String> questions, Set<String> answers, Set<String> plans,
            Set<String> critiques, Set<String> repairs) {
        public Seen(Set<String> requests, Set<String> questions, Set<String> answers, Set<String> plans,
                Set<String> critiques) {
            this(requests, questions, answers, plans, critiques, Set.of());
        }

        public Seen(Set<String> requests, Set<String> questions, Set<String> answers, Set<String> plans) {
            this(requests, questions, answers, plans, Set.of(), Set.of());
        }

        public Seen(Set<String> requests, Set<String> questions, Set<String> answers) {
            this(requests, questions, answers, Set.of(), Set.of(), Set.of());
        }
    }

    /** {@code spec} is the checked request (plans re-written) when {@code problem} is null. */
    public record Result(PromptSpec spec, String problem) {
        static Result refuse(String why) {
            return new Result(null, why);
        }
    }

    private PromptCheck() {
    }

    public static Result check(PromptSpec spec, Seen seen) {
        if (spec.kind() == null) {
            return Result.refuse("it has no kind");
        }
        if (!worldId(spec.dimension()) || !worldId(spec.biome())) {
            return Result.refuse("its dimension or biome isn't a plain id");
        }
        if (spec.style() != null && Guidance.Style.parse(spec.style()).isEmpty()) {
            return Result.refuse("its style isn't one of the known styles");
        }
        if (spec.direction() != null && !Guidance.DIRECTIONS.contains(spec.direction())) {
            return Result.refuse("its creative direction isn't one of the known ones");
        }
        if (spec.constraints().materials().stream().anyMatch(id -> !knownBlock(id)))
            return Result.refuse("its allowed materials contain an unknown block");
        PromptSpec.Placement p = spec.placement();
        if (p != null && (p.distance() < 0 || p.distance() > 512 || Math.abs(p.x()) > 512 || Math.abs(p.y()) > 512
                || Math.abs(p.z()) > 512)) {
            return Result.refuse("its placement is out of range");
        }
        if (spec.request() == null || spec.request().isBlank() || spec.request().length() > MAX_TEXT
                || !contains(seen.requests(), spec.request())) {
            return Result.refuse("it asks for something you didn't ask for");
        }
        if (spec.exchanges().size() > 5) {
            return Result.refuse("it has too many questions");
        }
        for (Exchange e : spec.exchanges()) {
            if (e.question() == null || !contains(seen.questions(), e.question())) {
                return Result.refuse("it has a question your AI didn't ask");
            }
            if (e.answer() == null || !contains(seen.answers(), e.answer())) {
                return Result.refuse("it has an answer you didn't give");
            }
        }
        if (spec.remaining() < 0 || spec.remaining() > 5 || spec.maxBlocks() < 1 || spec.maxBlocks() > 1_000_000) {
            return Result.refuse("its limits are out of range");
        }
        boolean reviewing = spec.kind() == PromptSpec.Kind.REVIEW || spec.kind() == PromptSpec.Kind.CRITIQUE;
        if (reviewing
                && (spec.rounds() < 1 || spec.rounds() > 2 || spec.round() < 1 || spec.round() > spec.rounds())) {
            return Result.refuse("its review round is out of range");
        }
        String critique = critiqueProblem(spec, seen);
        if (critique != null) {
            return Result.refuse(critique);
        }
        if (spec.inspectionRemaining() < 0 || spec.inspectionRemaining() > InspectionRequest.MAX_ROUNDS
                || spec.inspections().size() > InspectionRequest.MAX_ROUNDS + 1
                || ((spec.kind() == PromptSpec.Kind.SHRINK || spec.kind() == PromptSpec.Kind.CRITIQUE)
                        && spec.inspectionRemaining() != 0)) {
            return Result.refuse("its world inspection limits are out of range");
        }
        // BuildArea, PlanningAreas, ProjectTheme, and WorldSnapshot validate their shape on construction; their block ids
        // are checked against this game's registry in blockFieldsProblem.
        if (spec.feedback().planKey() != null && !seen.plans().contains(spec.feedback().planKey()))
            return Result.refuse("its feedback is not about a plan your AI returned");
        if (spec.feedback().planKey() != null && reviewing
                && !spec.feedback().planKey().equals(planKey(spec.planJson())))
            return Result.refuse("its feedback does not match the review plan");
        if (spec.kind() == PromptSpec.Kind.NEW && spec.areas().focus() != null) {
            return Result.refuse("a new build should not carry a focused edit area");
        }
        String guides = guidesProblem(spec);
        if (guides != null) {
            return Result.refuse(guides);
        }
        String terrain = terrainProblem(spec.terrain());
        if (terrain != null) {
            return Result.refuse(terrain);
        }
        String blocks = blockFieldsProblem(spec);
        if (blocks != null) {
            return Result.refuse(blocks);
        }
        boolean editContext = spec.kind() == PromptSpec.Kind.EDIT
                || ((reviewing || spec.kind() == PromptSpec.Kind.SHRINK)
                        && spec.originalRequest() != null);
        if (spec.proposal() && !editContext) {
            return Result.refuse("only a revision can describe an unplaced proposal");
        }
        if (editContext) {
            if (spec.originalRequest() == null || spec.originalRequest().length() > MAX_HISTORY_TEXT
                    || spec.earlierChanges().size() > MAX_HISTORY
                    || spec.earlierChanges().stream().anyMatch(c -> c == null || c.length() > MAX_HISTORY_TEXT)) {
                return Result.refuse("its build history is too long");
            }
        } else if (spec.originalRequest() != null || !spec.earlierChanges().isEmpty()) {
            return Result.refuse("it has build history it shouldn't have");
        }
        if (spec.kind() == PromptSpec.Kind.NEW) {
            return spec.planJson() == null ? new Result(spec, null) : Result.refuse("a new build shouldn't carry a plan");
        }
        if (spec.planJson() == null || spec.planJson().length() > MAX_PLAN) {
            return Result.refuse("its plan is missing or too large");
        }
        BuildPlan plan;
        try {
            plan = spec.plan();
        } catch (PlanException e) {
            return Result.refuse("its plan doesn't parse (" + e.getMessage() + ")");
        }
        String candidateKey = planKey(spec.planJson());
        if (reviewing || spec.kind() == PromptSpec.Kind.SHRINK) {
            if (!seen.plans().contains(candidateKey)) {
                return Result.refuse("its plan is one your AI didn't return");
            }
        } else {
            // The server's saved build: only its blocks and shapes reach the AI.
            plan = PlanSanitizer.withoutSummary(PlanSanitizer.sanitize(plan, PromptCheck::knownBlock));
        }
        String binding = critiqueBindingProblem(spec, seen, candidateKey);
        if (binding != null) {
            return Result.refuse(binding);
        }
        try {
            new PlanLimits(MAX_OPERATIONS, spec.maxBlocks(), MAX_SIZE).check(plan);
        } catch (PlanException e) {
            // An observed rejected draft may be repaired, but PromptSpec must never rasterize it unchecked.
            if (spec.kind() != PromptSpec.Kind.REVIEW || !candidateKey.equals(spec.feedback().planKey())
                    || spec.feedback().problems().stream()
                    .noneMatch(problem -> problem.kind() == dev.justbuild.prompt.PlanningFeedback.Kind.LIMIT))
                return Result.refuse("its plan is beyond what this game will draw (" + e.getMessage() + ")");
        }
        // Re-written from what was parsed, so nothing but the plan's own fields goes to the AI.
        return new Result(spec.withPlan(PlanWriter.write(plan)), null);
    }

    /**
     * A critique travels only as one this game's AI returned: a repair carries the critique of the plan it repairs, and
     * only a second review round carries an earlier critique with its repair outcome. The outcome is a fixed enum the
     * server chooses; it renders as one of six fixed sentences and carries no server text. {@link
     * #critiqueBindingProblem} then ties the critique to the plan, once that plan has parsed.
     */
    private static String critiqueProblem(PromptSpec spec, Seen seen) {
        CritiqueNote note = spec.critique();
        if (note == null) {
            return null;
        }
        if (!seen.critiques().contains(note.critique().key())) {
            return "it carries a critique your AI didn't return";
        }
        return switch (spec.kind()) {
            case REVIEW -> note.outcome() != null ? "its repair carries a repair outcome"
                    : !note.critique().needsRepair() ? "its repair has nothing to fix"
                    : null;
            case CRITIQUE -> spec.round() != 2 ? "only a second review carries an earlier critique"
                    : note.outcome() == null ? "a second review's earlier critique needs its repair outcome"
                    : null;
            default -> "it carries a critique it shouldn't have";
        };
    }

    /**
     * A repair's critique is about the plan it repairs. A second review's earlier critique is about the candidate
     * (a rejected or failed repair leaves the plan unchanged), or, after an accepted repair, this game recorded that a
     * repair carrying that critique returned the candidate.
     */
    private static String critiqueBindingProblem(PromptSpec spec, Seen seen, String candidateKey) {
        CritiqueNote note = spec.critique();
        if (note == null) {
            return null;
        }
        boolean about = note.critique().planKey().equals(candidateKey);
        if (spec.kind() == PromptSpec.Kind.REVIEW) {
            return about ? null : "its critique is about a different plan";
        }
        boolean repaired = note.outcome() == RepairOutcome.ACCEPTED
                && seen.repairs().contains(note.critique().key() + ">" + candidateKey);
        return about || repaired ? null : "its earlier critique is about a different plan";
    }

    /** Guide ids must be ones this game has; their text always comes from the game's own copy. */
    private static String guidesProblem(PromptSpec spec) {
        GuideLibrary library = GuideLibrary.defaults();
        if (spec.guides().size() > GuideLibrary.MAX_TOTAL || spec.guides().stream().distinct().count()
                != spec.guides().size() || spec.guides().stream().anyMatch(
                        id -> id == null || library.all().stream().noneMatch(g -> g.id().equals(id)))) {
            return "it lists guides your game doesn't have";
        }
        if (spec.kind() == PromptSpec.Kind.SHRINK && !spec.guides().isEmpty()) {
            return "it adds guides to a smaller-plan request";
        }
        // The menu is offered on a new build or edit (beside any guides the server attached), never in a review or a
        // smaller-plan request.
        boolean planning = spec.kind() == PromptSpec.Kind.NEW || spec.kind() == PromptSpec.Kind.EDIT;
        if (spec.offerGuides() && !planning) {
            return "it offers guides where it shouldn't";
        }
        return null;
    }

    private static String terrainProblem(TerrainSummary t) {
        if (t == null) {
            return null;
        }
        if (t.step() < 1 || t.step() > 16 || Math.abs(t.minX()) > 512 || Math.abs(t.minZ()) > 512
                || t.heights().length > MAX_TERRAIN) {
            return "its terrain is out of range";
        }
        for (int[] row : t.heights()) {
            if (row.length > MAX_TERRAIN) {
                return "its terrain is out of range";
            }
        }
        if (t.surface().size() > MAX_SURFACE) {
            return "its terrain lists too many blocks";
        }
        for (Map.Entry<String, Integer> s : t.surface().entrySet()) {
            if (!id(s.getKey()) || s.getValue() == null || s.getValue() < 0) {
                return "its terrain has a block that isn't a plain id";
            }
            // The server names surface blocks without their namespace, so a modded block matches by path.
            if (!knownBlock(s.getKey()) && net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet().stream()
                    .noneMatch(key -> key.getPath().equals(s.getKey()))) {
                return "its terrain names a block your game doesn't have";
            }
        }
        return null;
    }

    /**
     * World samples, the project palette, and block lookups reach the AI verbatim. Their grammars admit id-shaped prose
     * (ignore_all_rules:do_this), so each entry must be a block or block state this game has, and a lookup must match
     * part of a real block id.
     */
    private static String blockFieldsProblem(PromptSpec spec) {
        Set<String> states = new java.util.HashSet<>();
        spec.inspections().forEach(snapshot -> states.addAll(snapshot.palette()));
        dev.justbuild.prompt.PlanningFeedback feedback = spec.feedback();
        java.util.stream.Stream.concat(java.util.stream.Stream.of(feedback.source(), feedback.candidate()),
                feedback.inspections().stream()).filter(java.util.Objects::nonNull)
                .forEach(volume -> states.addAll(volume.palette()));
        states.addAll(spec.theme().palette());
        if (states.stream().anyMatch(state -> !knownBlock(state))) {
            return "its world samples or palette name a block your game doesn't have";
        }
        for (var query : feedback.blockQueries()) {
            if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet().stream()
                    .noneMatch(key -> key.toString().contains(query.query()))) {
                return "its block lookup matches no block";
            }
        }
        return null;
    }

    private static boolean id(String text) {
        return text != null && ID.matcher(text).matches();
    }

    private static boolean worldId(String text) {
        return text != null && text.length() <= MAX_WORLD_ID && WORLD_ID.matcher(text).matches();
    }

    private static boolean contains(Set<String> seen, String text) {
        String wanted = normal(text);
        return seen.stream().anyMatch(s -> normal(s).equals(wanted));
    }

    /** Case and spacing don't matter when matching what the player typed. */
    static String normal(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
