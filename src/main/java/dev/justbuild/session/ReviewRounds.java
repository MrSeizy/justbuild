package dev.justbuild.session;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Critique;
import dev.justbuild.plan.CritiqueNote;
import dev.justbuild.plan.CritiqueParser;
import dev.justbuild.plan.PlanException;
import dev.justbuild.plan.PlanLimits;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.plan.PlanResponse;
import dev.justbuild.plan.PlanSize;
import dev.justbuild.plan.PlanWriter;
import dev.justbuild.plan.RepairOutcome;
import dev.justbuild.prompt.PlanningFeedback;
import dev.justbuild.provider.CancelledException;
import dev.justbuild.provider.ProviderException;
import dev.justbuild.relay.PromptCheck;
import dev.justbuild.relay.PromptSpec;
import dev.justbuild.world.ReviewCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The review rounds after a draft, shared by planning and the quality gallery. In {@link Mode#SEPARATE} a reviewer
 * critiques the candidate and the builder repairs it only when the critique calls for it or the draft's checks find a
 * technical failure (any diagnosed problem when the critique fails); a second round runs only after the first repaired.
 * Detail level 0 repairs once when the checks need a correction.
 * {@link Mode#SELF} is the earlier self-review, kept so the gallery can compare the two.
 */
public final class ReviewRounds {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("JustBuild");

    public enum Mode { SEPARATE, SELF }

    public enum Stage {
        /** The reviewer is critiquing the current candidate. */
        CRITIQUE,
        /** The builder is applying a critique, or the draft's own checks, to the candidate. */
        REPAIR,
        /** The builder is reviewing its own plan: {@link Mode#SELF}, or the quick-mode correction at detail level 0. */
        REVIEW
    }

    /** What the rounds need from whoever runs them. Called on a worker thread. */
    public interface Caller {
        /** The reviewer's raw answer to a CRITIQUE spec. The spec carries zero inspection reads and must stay that way (the relay refuses otherwise). */
        String critique(PromptSpec spec) throws ProviderException, PlanException;

        /**
         * The builder's raw answer to a REVIEW spec, after any inspection rounds it asks for. The spec arrives with zero
         * inspection reads ({@code PromptSpec.asking} zeroes them); a caller that offers inspection must restore the shared budget.
         */
        String repair(PromptSpec spec) throws ProviderException, PlanException;

        /** Compares the current candidate with its repair on one world snapshot; the feedback describes the plan kept. */
        Compared compare(BuildPlan current, BuildPlan repaired) throws ProviderException;

        /** The review is starting {@code stage} of {@code round} of {@code rounds}. */
        void stage(Stage stage, int round, int rounds);

        /** A progress line for the player. */
        void say(String line);
    }

    public record Compared(ReviewCheck.Verdict verdict, PlanningFeedback feedback) { }

    /**
     * One round. {@code critique} is null when the critique failed or was skipped (or in self-review); {@code repairRan}
     * says a repair was attempted; {@code outcome} is null when no repair ran; {@code unchanged}: the round left the plan
     * as it was (no repair, a rejection, a failure, or an identical repair).
     */
    public record Round(int number, Critique critique, boolean repairRan, RepairOutcome outcome, boolean unchanged) { }

    /** The plan kept after the rounds, the raw answer that produced it, and what each round did. */
    public record Result(BuildPlan plan, String raw, List<Round> rounds) { }

    private record Repair(BuildPlan plan, String raw, PlanningFeedback evidence, RepairOutcome outcome, boolean unchanged) { }

    private final Mode mode;
    private final PlanLimits limits;
    private final Caller caller;

    public ReviewRounds(Mode mode, PlanLimits limits, Caller caller) {
        this.mode = mode;
        this.limits = limits;
        this.caller = caller;
    }

    /** {@code base} is the draft's request spec, {@code evidence} the draft's checks, {@code rounds} the detail level's. */
    public Result run(PromptSpec base, BuildPlan plan, String raw, PlanningFeedback evidence, int rounds)
            throws CancelledException {
        List<Round> done = new ArrayList<>();
        if (rounds == 0 || mode == Mode.SELF) {
            int total = rounds == 0 ? (evidence.needsCorrection() ? 1 : 0) : rounds;
            for (int round = 1; round <= total; round++) {
                caller.stage(Stage.REVIEW, round, total);
                Repair r = repair(base, plan, raw, evidence, null, round, total);
                done.add(new Round(round, null, true, r.outcome(), r.unchanged()));
                plan = r.plan();
                raw = r.raw();
                evidence = r.evidence();
            }
            return new Result(plan, raw, List.copyOf(done));
        }
        CritiqueNote previous = null;
        for (int round = 1; round <= rounds; round++) {
            // A draft over the limits has no safe views to judge; its diagnostics go straight to a repair.
            boolean drawable = evidence.problems().stream().noneMatch(p -> p.kind() == PlanningFeedback.Kind.LIMIT);
            Critique critique = null;
            if (drawable) {
                caller.stage(Stage.CRITIQUE, round, rounds);
                critique = critique(base, plan, evidence, round, rounds, previous);
            } else {
                LOGGER.debug("Review round {}: draft over the limits; skipping the critique", round);
            }
            boolean worth = critique != null && critique.needsRepair();
            // The reviewer read the draft's checks, so once it answers only technical failures force a repair; advisory
            // findings (stairs, foundations, support) are its call. Without a critique, any diagnosed problem gets one.
            boolean checksNeedRepair = critique != null ? evidence.technicalFailures() > 0 : evidence.needsCorrection();
            if (!worth && !checksNeedRepair) {
                caller.say(critique != null ? "Reviewer: no changes needed."
                        : "Reviewer didn't answer; keeping " + kept(round) + ".");
                done.add(new Round(round, critique, false, null, true));
                break;
            }
            caller.say(worth ? found(critique) : drawable && critique == null
                    ? "Reviewer didn't answer; fixing the reported problems." : "Fixing the reported problems…");
            caller.stage(Stage.REPAIR, round, rounds);
            Repair r = repair(base, plan, raw, evidence, worth ? critique : null, round, rounds);
            if (r.outcome() != RepairOutcome.ACCEPTED) caller.say(rejected(r.outcome(), round));
            done.add(new Round(round, critique, true, r.outcome(), r.unchanged()));
            plan = r.plan();
            raw = r.raw();
            evidence = r.evidence();
            // Round 2 cites only a critique worth fixing: the repair carried it, so the relay can bind it (an unchanged or
            // rejected plan is what the critique is about; after an accepted change the game recorded the repair pair).
            // An ok or minor-only critique was never applied, and citing it with the repair's outcome would mislead.
            previous = worth ? new CritiqueNote(critique, r.outcome()) : null;
        }
        return new Result(plan, raw, List.copyOf(done));
    }

    /** The reviewer's findings, or null when it failed or answered with something other than a critique. */
    private Critique critique(PromptSpec base, BuildPlan plan, PlanningFeedback evidence, int round, int rounds,
            CritiqueNote previous) throws CancelledException {
        String planJson = PlanWriter.write(plan);
        try {
            String answer = caller.critique(base.critiqueRound(planJson, round, rounds, previous).withFeedback(evidence));
            return CritiqueParser.parse(answer, plan, PromptCheck.planKey(planJson));
        } catch (CancelledException e) {
            throw e;
        } catch (PlanException | ProviderException e) {
            LOGGER.warn("Review round {} critique failed ({}); keeping the plan unless its checks need a fix", round,
                    e.getMessage());
            return null;
        }
    }

    /** One repair; anything unusable or rejected keeps {@code plan} and says why. */
    private Repair repair(PromptSpec base, BuildPlan plan, String raw, PlanningFeedback evidence, Critique critique,
            int round, int rounds) throws CancelledException {
        String planJson = PlanWriter.write(plan);
        try {
            PromptSpec spec = base.review(planJson, round, rounds, critique == null ? null : new CritiqueNote(critique, null))
                    .withFeedback(evidence);
            String answer = caller.repair(spec);
            if (!(PlanParser.parse(answer) instanceof PlanResponse.Build better)) {
                LOGGER.warn("Review round {} did not return a build; keeping the previous plan", round);
                return new Repair(plan, raw, evidence, RepairOutcome.FAILED, true);
            }
            try {
                limits.check(better.plan());
                if (PlanSize.blocks(better.plan(), limits.maxBlocks()) == PlanSize.OVER) {
                    LOGGER.warn("Review round {} went over {} blocks; keeping the previous plan", round, limits.maxBlocks());
                    return new Repair(plan, raw, evidence, RepairOutcome.REJECTED_LIMIT, true);
                }
            } catch (PlanException e) {
                // Too many operations, a bad scalar, a box too big, or shapes that take too much work to draw.
                LOGGER.warn("Review round {} went over the limits ({}); keeping the previous plan", round, e.getMessage());
                return new Repair(plan, raw, evidence, RepairOutcome.REJECTED_LIMIT, true);
            }
            // A world-check failure (ProviderException) surfaces here and is reported as FAILED by the catch below.
            Compared compared = caller.compare(plan, better.plan());
            if (!compared.verdict().accepted()) {
                LOGGER.warn("Review round {} {}; keeping the previous plan", round, compared.verdict().reason());
                return new Repair(plan, raw, describing(compared.feedback(), plan, evidence, round),
                        compared.verdict().outcome(), true);
            }
            String feedbackKey = compared.feedback().planKey();
            if (feedbackKey != null && !feedbackKey.equals(PromptCheck.planKey(PlanWriter.write(better.plan())))) {
                // The verdict may have compared the wrong plans, so it cannot be trusted: keep the plan with its evidence.
                LOGGER.warn("Review round {}: review checks described a different plan; treating the repair as failed", round);
                return new Repair(plan, raw, evidence, RepairOutcome.FAILED, true);
            }
            return new Repair(better.plan(), answer, compared.feedback(), RepairOutcome.ACCEPTED,
                    PlanWriter.write(better.plan()).equals(planJson));
        } catch (CancelledException e) {
            throw e;
        } catch (PlanException | ProviderException e) {
            LOGGER.warn("Review round {} repair failed ({}); keeping the previous plan", round, e.getMessage());
            return new Repair(plan, raw, evidence, RepairOutcome.FAILED, true);
        }
    }

    /** The checks for the plan kept: feedback keyed to another plan would mislead the next round, so the earlier evidence stays. */
    private static PlanningFeedback describing(PlanningFeedback feedback, BuildPlan kept, PlanningFeedback earlier, int round) {
        if (feedback.planKey() == null || feedback.planKey().equals(PromptCheck.planKey(PlanWriter.write(kept)))) return feedback;
        LOGGER.warn("Review round {}: review checks described a different plan; keeping the earlier evidence", round);
        return earlier;
    }

    private static String kept(int round) {
        return round == 1 ? "the draft" : "the previous version";
    }

    /** "Reviewer found 3 problems (access, roof, facade); revising…", made from typed fields only. */
    static String found(Critique critique) {
        long count = critique.problems().stream().filter(Critique.Problem::worthFixing).count();
        String areas = critique.categories().stream().map(c -> c.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
        return "Reviewer found " + count + (count == 1 ? " problem (" : " problems (") + areas + "); revising…";
    }

    static String rejected(RepairOutcome outcome, int round) {
        String kept = kept(round);
        return switch (outcome) {
            case REJECTED_TECHNICAL -> "Revision rejected (it added technical problems); keeping " + kept + ".";
            case REJECTED_REMOVAL -> "Revision rejected (it removed existing structure); keeping " + kept + ".";
            case REJECTED_ROUTE -> "Revision rejected (it blocked a doorway or route); keeping " + kept + ".";
            case REJECTED_LIMIT -> "Revision rejected (it went over the size limits); keeping " + kept + ".";
            case FAILED -> "Revision failed; keeping " + kept + ".";
            case ACCEPTED -> "Revision applied.";
        };
    }
}
