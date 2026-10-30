package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Critique;
import dev.justbuild.plan.CritiqueParser;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanLimits;
import dev.justbuild.plan.PlanParser;
import dev.justbuild.plan.PlanResponse;
import dev.justbuild.plan.PlanWriter;
import dev.justbuild.plan.RepairOutcome;
import dev.justbuild.prompt.PlanningFeedback;
import dev.justbuild.provider.CancelledException;
import dev.justbuild.provider.ProviderException;
import dev.justbuild.relay.PromptCheck;
import dev.justbuild.relay.PromptSpec;
import dev.justbuild.world.McBootstrap;
import dev.justbuild.world.ReviewCheck;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReviewRoundsTest {
    static final BuildPlan DRAFT = new BuildPlan("Hut", "A hut", List.of(Operation.set("minecraft:stone", new Cell(0, 0, 0))));
    static final BuildPlan REPAIRED = new BuildPlan("Hut", "A hut", List.of(Operation.set("minecraft:stone", new Cell(0, 0, 0)),
            Operation.set("minecraft:oak_planks", new Cell(0, 1, 0))));
    static final String OK = "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":[],\"keep\":[]}";
    static final String MINOR = "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":[{\"severity\":\"minor\",\"category\":\"facade\","
            + "\"operations\":[0],\"cell\":null,\"problem\":\"Plain wall.\",\"fix\":\"Add a window.\"}],\"keep\":[]}";
    static final String SHOULD = MINOR.replace("\"minor\"", "\"should_fix\"").replace("\"ok\"", "\"revise\"");
    static final BuildPlan REPAIRED2 = new BuildPlan("Hut", "A hut", List.of(Operation.set("minecraft:stone", new Cell(0, 0, 0)),
            Operation.set("minecraft:oak_planks", new Cell(0, 1, 0)), Operation.set("minecraft:glass", new Cell(1, 1, 0))));
    static final String NOT_A_PLAN = "this is not a plan";
    static final PlanLimits LIMITS = new PlanLimits(2000, 250000, 96);
    static final PromptSpec BASE = PromptSpec.newBuild("minecraft:overworld", "minecraft:plains", PromptSpec.Placement.FRONT,
            null, null, "a hut", List.of(), 0, null, 250000);

    @BeforeAll static void bootstrap() { McBootstrap.init(); }

    static String key(BuildPlan plan) { return PromptCheck.planKey(PlanWriter.write(plan)); }

    static String problem(String severity, String category) {
        return "{\"severity\":\"" + severity + "\",\"category\":\"" + category + "\",\"operations\":[0],\"cell\":null,"
                + "\"problem\":\"A problem.\",\"fix\":\"A fix.\"}";
    }

    static PlanningFeedback evidence(PlanningFeedback.Kind... kinds) {
        return new PlanningFeedback(PromptCheck.planKey(PlanWriter.write(DRAFT)),
                Arrays.stream(kinds).map(k -> new PlanningFeedback.Problem(k, null)).toList(), null, null, null, null);
    }

    static final class Fake implements ReviewRounds.Caller {
        final Deque<String> critiques = new ArrayDeque<>();
        final Deque<String> repairs = new ArrayDeque<>();
        final List<PromptSpec> asked = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        final List<String> stages = new ArrayList<>();
        ReviewCheck.Verdict verdict = new ReviewCheck.Verdict(true, null, RepairOutcome.ACCEPTED);
        ProviderException failure;
        ProviderException repairFailure;
        ProviderException compareFailure;
        boolean wrongKey;
        /** What the review checks find in the plan kept after a repair. */
        PlanningFeedback.Kind[] afterRepair = {};
        final Set<String> critiqueKeys = new LinkedHashSet<>();

        Fake critiques(String... answers) { critiques.addAll(List.of(answers)); return this; }
        Fake repairs(String... answers) { repairs.addAll(List.of(answers)); return this; }

        @Override public String critique(PromptSpec spec) throws ProviderException {
            asked.add(spec);
            if (failure != null) throw failure;
            String next = critiques.poll();
            if (next == null) throw new ProviderException("the reviewer did not answer");
            try {
                critiqueKeys.add(CritiqueParser.parse(next, ((PlanResponse.Build) PlanParser.parse(spec.planJson())).plan(),
                        PromptCheck.planKey(spec.planJson())).key());
            } catch (Exception e) {
                // not a critique: the loop treats it as a failure
            }
            return next;
        }
        @Override public String repair(PromptSpec spec) throws ProviderException {
            asked.add(spec);
            if (repairFailure != null) throw repairFailure;
            return Objects.requireNonNull(repairs.poll(), "unexpected repair call");
        }
        @Override public ReviewRounds.Compared compare(BuildPlan current, BuildPlan repaired) throws ProviderException {
            if (compareFailure != null) throw compareFailure;
            BuildPlan kept = verdict.accepted() ? repaired : current;
            BuildPlan keyed = wrongKey ? (kept == repaired ? current : repaired) : kept;
            return new ReviewRounds.Compared(verdict, new PlanningFeedback(key(keyed),
                    Arrays.stream(afterRepair).map(k -> new PlanningFeedback.Problem(k, null)).toList(), null, null, null, null));
        }
        @Override public void stage(ReviewRounds.Stage stage, int round, int rounds) { stages.add(stage + " " + round + "/" + rounds); }
        @Override public void say(String line) { lines.add(line); }

        List<PromptSpec.Kind> kinds() { return asked.stream().map(PromptSpec::kind).toList(); }
    }

    static ReviewRounds.Result run(Fake fake, ReviewRounds.Mode mode, PlanningFeedback evidence, int rounds) throws Exception {
        return new ReviewRounds(mode, LIMITS, fake).run(BASE, DRAFT, PlanWriter.write(DRAFT), evidence, rounds);
    }

    static ReviewRounds.Result run(Fake fake, int rounds) throws Exception {
        return run(fake, ReviewRounds.Mode.SEPARATE, evidence(), rounds);
    }

    @Test
    void anOkCritiqueEndsTheReviewAfterOneShortCall() throws Exception {
        Fake fake = new Fake().critiques(OK);
        var result = run(fake, 2);
        assertEquals(DRAFT, result.plan());
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE), fake.kinds());
        assertEquals(List.of("CRITIQUE 1/2"), fake.stages);
        assertEquals(List.of("Reviewer: no changes needed."), fake.lines);
        assertFalse(result.rounds().get(0).repairRan());
    }

    @Test
    void minorProblemsNeedNoRepair() throws Exception {
        Fake fake = new Fake().critiques(MINOR);
        run(fake, 1);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE), fake.kinds());
    }

    @Test
    void aProblemWorthFixingIsRepairedAndCheckedAgainInRoundTwo() throws Exception {
        Fake fake = new Fake().critiques(SHOULD, OK).repairs(PlanWriter.write(REPAIRED));
        var result = run(fake, 2);
        assertEquals(PlanWriter.write(REPAIRED), PlanWriter.write(result.plan()));
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW, PromptSpec.Kind.CRITIQUE), fake.kinds());
        Critique first = fake.asked.get(1).critique().critique();
        assertEquals(PromptCheck.planKey(PlanWriter.write(DRAFT)), first.planKey(), "the repair carries the draft's critique");
        assertNull(fake.asked.get(1).critique().outcome());
        assertEquals(2, fake.asked.get(2).round());
        assertEquals(RepairOutcome.ACCEPTED, fake.asked.get(2).critique().outcome());
        assertEquals(PlanWriter.write(REPAIRED), fake.asked.get(2).planJson(), "round 2 reviews the repaired plan");
        assertEquals(List.of("Reviewer found 1 problem (facade); revising…", "Reviewer: no changes needed."), fake.lines);
        assertEquals(List.of("CRITIQUE 1/2", "REPAIR 1/2", "CRITIQUE 2/2"), fake.stages);
        assertEquals(key(REPAIRED), fake.asked.get(2).feedback().planKey(), "the evidence describes the plan kept");
        assertEquals(PlanWriter.write(REPAIRED), result.raw());
    }

    @Test
    void technicalFailuresRepairEvenWhenTheReviewerIsSatisfied() throws Exception {
        for (PlanningFeedback.Kind kind : List.of(PlanningFeedback.Kind.ZONE, PlanningFeedback.Kind.INVALID_BLOCKS)) {
            Fake fake = new Fake().critiques(OK).repairs(PlanWriter.write(REPAIRED));
            run(fake, ReviewRounds.Mode.SEPARATE, evidence(kind, PlanningFeedback.Kind.STAIR), 1);
            assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW), fake.kinds(), kind.name());
            assertNull(fake.asked.get(1).critique(), "nothing from an ok critique is applied");
            assertEquals("Fixing the reported problems…", fake.lines.get(0));
        }
    }

    @Test
    void advisoryFindingsAreTheReviewersCall() throws Exception {
        for (PlanningFeedback.Kind kind : List.of(PlanningFeedback.Kind.STAIR, PlanningFeedback.Kind.FOUNDATION,
                PlanningFeedback.Kind.SUPPORT, PlanningFeedback.Kind.DOOR)) {
            Fake fake = new Fake().critiques(OK);
            var result = run(fake, ReviewRounds.Mode.SEPARATE, evidence(kind), 1);
            assertEquals(List.of(PromptSpec.Kind.CRITIQUE), fake.kinds(), kind + ": the reviewer read the finding and saw no problem");
            assertEquals(List.of("CRITIQUE 1/1"), fake.stages);
            assertEquals(List.of("Reviewer: no changes needed."), fake.lines);
            assertEquals(DRAFT, result.plan());
            assertEquals(PlanWriter.write(DRAFT), result.raw());
            assertFalse(result.rounds().get(0).repairRan());
        }
    }

    @Test
    void withoutACritiqueAdvisoryFindingsStillGetARepair() throws Exception {
        Fake failed = new Fake();
        failed.failure = new ProviderException("the reviewer could not be reached");
        failed.repairs(PlanWriter.write(REPAIRED));
        run(failed, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.STAIR), 1);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW), failed.kinds());
        assertNull(failed.asked.get(1).critique());
        assertEquals(List.of("Reviewer didn't answer; fixing the reported problems."), failed.lines);
        Fake notACritique = new Fake().critiques(PlanWriter.write(DRAFT)).repairs(PlanWriter.write(REPAIRED));
        run(notACritique, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.STAIR), 1);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW), notACritique.kinds(), "a plan is not a critique");
        assertNull(notACritique.asked.get(1).critique());
    }

    @Test
    void roundTwoLeavesAdvisoryFindingsToTheReviewer() throws Exception {
        Fake fake = new Fake().critiques(SHOULD, OK).repairs(PlanWriter.write(REPAIRED));
        fake.afterRepair = new PlanningFeedback.Kind[] {PlanningFeedback.Kind.STAIR};
        var result = run(fake, 2);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW, PromptSpec.Kind.CRITIQUE), fake.kinds());
        assertEquals(List.of("CRITIQUE 1/2", "REPAIR 1/2", "CRITIQUE 2/2"), fake.stages);
        assertEquals(List.of(PlanningFeedback.Kind.STAIR),
                fake.asked.get(2).feedback().problems().stream().map(PlanningFeedback.Problem::kind).toList(),
                "round 2's reviewer sees the repaired plan's finding");
        assertEquals(PlanWriter.write(REPAIRED), PlanWriter.write(result.plan()));
        assertEquals(RepairOutcome.ACCEPTED, result.rounds().get(0).outcome());
        assertFalse(result.rounds().get(1).repairRan());
        assertEquals("Reviewer: no changes needed.", fake.lines.get(fake.lines.size() - 1));
    }

    @Test
    void aSatisfiedCritiqueIsNotCitedAfterAChecksOnlyRepairChangedThePlan() throws Exception {
        Fake fake = new Fake().critiques(OK, OK).repairs(PlanWriter.write(REPAIRED));
        run(fake, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.ZONE), 2);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW, PromptSpec.Kind.CRITIQUE), fake.kinds());
        assertNull(fake.asked.get(2).critique(), "the ok critique is about the old plan and no repair carried it");
        assertEquals(PlanWriter.write(REPAIRED), fake.asked.get(2).planJson());
    }

    @Test
    void aSatisfiedCritiqueIsNotCitedAfterAnIdenticalOrRejectedChecksOnlyRepair() throws Exception {
        Fake same = new Fake().critiques(OK, OK).repairs(PlanWriter.write(DRAFT));
        run(same, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.ZONE), 2);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW, PromptSpec.Kind.CRITIQUE), same.kinds());
        assertNull(same.asked.get(2).critique());
        Fake rejected = new Fake().critiques(OK, OK).repairs(PlanWriter.write(REPAIRED));
        rejected.verdict = new ReviewCheck.Verdict(false, "removed a wall", RepairOutcome.REJECTED_REMOVAL);
        run(rejected, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.ZONE), 2);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW, PromptSpec.Kind.CRITIQUE), rejected.kinds());
        assertNull(rejected.asked.get(2).critique());
    }

    @Test
    void aRejectedRepairKeepsThePlanAndTellsTheNextRound() throws Exception {
        Fake fake = new Fake().critiques(SHOULD, OK).repairs(PlanWriter.write(REPAIRED));
        fake.verdict = new ReviewCheck.Verdict(false, "blocked a usable doorway at 0, 1, 0", RepairOutcome.REJECTED_ROUTE);
        var result = run(fake, 2);
        assertEquals(DRAFT, result.plan());
        assertEquals(RepairOutcome.REJECTED_ROUTE, fake.asked.get(2).critique().outcome());
        assertEquals(PlanWriter.write(DRAFT), fake.asked.get(2).planJson());
        assertTrue(fake.lines.contains("Revision rejected (it blocked a doorway or route); keeping the draft."));
        assertEquals(RepairOutcome.REJECTED_ROUTE, result.rounds().get(0).outcome());
        assertEquals(key(DRAFT), fake.asked.get(2).feedback().planKey(), "the evidence describes the plan kept");
        assertEquals(PlanWriter.write(DRAFT), result.raw());
    }

    @Test
    void aFailedRepairKeepsThePlanAndTheEarlierEvidence() throws Exception {
        Fake fake = new Fake().critiques(SHOULD, OK).repairs(NOT_A_PLAN);
        var result = run(fake, 2);
        assertEquals(DRAFT, result.plan());
        assertEquals(PlanWriter.write(DRAFT), result.raw());
        assertEquals(RepairOutcome.FAILED, result.rounds().get(0).outcome());
        assertEquals(RepairOutcome.FAILED, fake.asked.get(2).critique().outcome());
        assertEquals(key(DRAFT), fake.asked.get(2).feedback().planKey());
        assertEquals(PlanWriter.write(DRAFT), fake.asked.get(2).planJson());
        Fake thrown = new Fake().critiques(SHOULD, OK);
        thrown.repairFailure = new ProviderException("the builder did not answer");
        var after = run(thrown, 2);
        assertEquals(DRAFT, after.plan());
        assertEquals(RepairOutcome.FAILED, thrown.asked.get(2).critique().outcome());
        assertEquals(key(DRAFT), thrown.asked.get(2).feedback().planKey());
    }

    @Test
    void feedbackAboutAnotherPlanIsNotTrusted() throws Exception {
        Fake fake = new Fake().critiques(SHOULD, OK).repairs(PlanWriter.write(REPAIRED));
        fake.verdict = new ReviewCheck.Verdict(false, "removed a wall", RepairOutcome.REJECTED_REMOVAL);
        fake.wrongKey = true;
        run(fake, 2);
        assertEquals(key(DRAFT), fake.asked.get(2).feedback().planKey(), "the earlier evidence stays");
    }

    @Test
    void anAcceptedVerdictWithFeedbackAboutAnotherPlanIsAFailedRepair() throws Exception {
        Fake fake = new Fake().critiques(SHOULD, OK).repairs(PlanWriter.write(REPAIRED));
        fake.wrongKey = true;
        var result = run(fake, 2);
        assertEquals(DRAFT, result.plan());
        assertEquals(PlanWriter.write(DRAFT), result.raw());
        assertEquals(RepairOutcome.FAILED, result.rounds().get(0).outcome());
        assertEquals(RepairOutcome.FAILED, fake.asked.get(2).critique().outcome());
        assertEquals(PromptCheck.planKey(fake.asked.get(2).planJson()), fake.asked.get(2).feedback().planKey());
        assertEquals(PlanWriter.write(DRAFT), fake.asked.get(2).planJson());
    }

    @Test
    void aWorldCheckFailureIsAFailedRepair() throws Exception {
        Fake fake = new Fake().critiques(SHOULD, OK).repairs(PlanWriter.write(REPAIRED));
        fake.compareFailure = new ProviderException("the world could not be read");
        var result = run(fake, 2);
        assertEquals(DRAFT, result.plan());
        assertEquals(RepairOutcome.FAILED, result.rounds().get(0).outcome());
    }

    @Test
    void theWorstCaseRunsEveryStageAndRound2RepairsWithRound2sCritique() throws Exception {
        Fake fake = new Fake().critiques(SHOULD, SHOULD).repairs(PlanWriter.write(REPAIRED), PlanWriter.write(REPAIRED2));
        var result = run(fake, 2);
        assertEquals(List.of("CRITIQUE 1/2", "REPAIR 1/2", "CRITIQUE 2/2", "REPAIR 2/2"), fake.stages);
        assertEquals(PlanWriter.write(REPAIRED2), PlanWriter.write(result.plan()));
        assertEquals(RepairOutcome.ACCEPTED, fake.asked.get(2).critique().outcome());
        assertEquals(key(REPAIRED), fake.asked.get(3).critique().critique().planKey());
        assertNull(fake.asked.get(3).critique().outcome());
        assertEquals(PlanWriter.write(REPAIRED), fake.asked.get(3).planJson());
    }

    @Test
    void roundTwoLinesSayThePreviousVersionIsKept() throws Exception {
        Fake silent = new Fake().critiques(SHOULD).repairs(PlanWriter.write(REPAIRED));
        run(silent, 2);
        assertEquals("Reviewer didn't answer; keeping the previous version.", silent.lines.get(silent.lines.size() - 1));
        Fake failed = new Fake().critiques(SHOULD, SHOULD).repairs(PlanWriter.write(REPAIRED), NOT_A_PLAN);
        run(failed, 2);
        assertEquals("Revision failed; keeping the previous version.", failed.lines.get(failed.lines.size() - 1));
    }

    @Test
    void everyRequestTheLoopMakesPassesTheRelayCheck() throws Exception {
        Fake accepted = new Fake().critiques(SHOULD, OK).repairs(PlanWriter.write(REPAIRED));
        run(accepted, 2);
        assertRelayAccepts(accepted, true);
        Fake rejected = new Fake().critiques(SHOULD, OK).repairs(PlanWriter.write(REPAIRED));
        rejected.verdict = new ReviewCheck.Verdict(false, "removed a wall", RepairOutcome.REJECTED_REMOVAL);
        run(rejected, 2);
        assertRelayAccepts(rejected, false);
        Fake failed = new Fake().critiques(SHOULD, OK).repairs(NOT_A_PLAN);
        run(failed, 2);
        assertRelayAccepts(failed, false);
        assertEquals(3, failed.asked.size());
        Fake untrusted = new Fake().critiques(SHOULD, OK).repairs(PlanWriter.write(REPAIRED));
        untrusted.wrongKey = true;
        run(untrusted, 2);
        assertRelayAccepts(untrusted, false);
    }

    static void assertRelayAccepts(Fake fake, boolean repairReturnedRepaired) {
        Set<String> repairs = new HashSet<>();
        if (repairReturnedRepaired) repairs.add(fake.critiqueKeys.iterator().next() + ">" + key(REPAIRED));
        PromptCheck.Seen seen = new PromptCheck.Seen(Set.of("a hut"), Set.of(), Set.of(), Set.of(key(DRAFT), key(REPAIRED)),
                fake.critiqueKeys, repairs);
        for (PromptSpec spec : fake.asked) {
            assertNull(PromptCheck.check(spec, seen).problem(), spec.kind() + " round " + spec.round());
        }
    }

    @Test
    void unknownProblemsAloneNeedNoRepairButTechnicalOnesDo() throws Exception {
        Fake unknown = new Fake().critiques(OK);
        run(unknown, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.UNKNOWN), 1);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE), unknown.kinds());
        Fake zone = new Fake().critiques(OK).repairs(PlanWriter.write(REPAIRED));
        run(zone, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.UNKNOWN, PlanningFeedback.Kind.ZONE), 1);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW), zone.kinds());
    }

    @Test
    void foundCountsProblemsAndListsEachAreaOnce() throws Exception {
        String raw = "{\"kind\":\"critique\",\"verdict\":\"revise\",\"problems\":[" + problem("should_fix", "facade") + ","
                + problem("must_fix", "roof") + "," + problem("minor", "access") + "," + problem("should_fix", "facade")
                + "],\"keep\":[]}";
        Critique critique = CritiqueParser.parse(raw, DRAFT, key(DRAFT));
        assertEquals("Reviewer found 3 problems (facade, roof); revising…", ReviewRounds.found(critique));
        Critique two = CritiqueParser.parse("{\"kind\":\"critique\",\"verdict\":\"revise\",\"problems\":["
                + problem("should_fix", "facade") + "," + problem("should_fix", "facade") + "],\"keep\":[]}", DRAFT, key(DRAFT));
        assertEquals("Reviewer found 2 problems (facade); revising…", ReviewRounds.found(two));
    }

    @Test
    void aFailedCritiqueKeepsTheDraftUnlessChecksNeedAFix() throws Exception {
        Fake quiet = new Fake();
        assertEquals(DRAFT, run(quiet, 2).plan());
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE), quiet.kinds());
        assertEquals(List.of("Reviewer didn't answer; keeping the draft."), quiet.lines);
        Fake fixing = new Fake().repairs(PlanWriter.write(REPAIRED));
        run(fixing, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.SUPPORT), 1);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW), fixing.kinds());
        assertNull(fixing.asked.get(1).critique());
        assertEquals("Reviewer didn't answer; fixing the reported problems.", fixing.lines.get(0));
        Fake broken = new Fake().critiques(PlanWriter.write(DRAFT));
        run(broken, 1);
        assertEquals(List.of("Reviewer didn't answer; keeping the draft."), broken.lines, "a plan is not a critique");
    }

    @Test
    void draftsOverTheLimitsSkipTheCritique() throws Exception {
        Fake fake = new Fake().repairs(PlanWriter.write(REPAIRED));
        run(fake, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.LIMIT), 1);
        assertEquals(List.of(PromptSpec.Kind.REVIEW), fake.kinds());
        assertEquals(List.of("REPAIR 1/1"), fake.stages);
        assertEquals("Fixing the reported problems…", fake.lines.get(0));
    }

    @Test
    void repairsOverTheLimitsAreRejected() throws Exception {
        Fake fake = new Fake().critiques(SHOULD).repairs(PlanWriter.write(REPAIRED));
        var result = new ReviewRounds(ReviewRounds.Mode.SEPARATE, new PlanLimits(1, 250000, 96), fake)
                .run(BASE, DRAFT, PlanWriter.write(DRAFT), evidence(), 1);
        assertEquals(DRAFT, result.plan());
        assertEquals(RepairOutcome.REJECTED_LIMIT, result.rounds().get(0).outcome());
    }

    @Test
    void selfModeAndQuickCorrectionKeepTodaysSequence() throws Exception {
        Fake self = new Fake().repairs(PlanWriter.write(REPAIRED), PlanWriter.write(REPAIRED));
        run(self, ReviewRounds.Mode.SELF, evidence(), 2);
        assertEquals(List.of(PromptSpec.Kind.REVIEW, PromptSpec.Kind.REVIEW), self.kinds());
        assertTrue(self.asked.stream().allMatch(s -> s.critique() == null));
        assertTrue(self.lines.isEmpty());
        assertEquals(List.of("REVIEW 1/2", "REVIEW 2/2"), self.stages);
        assertEquals(PlanWriter.write(REPAIRED), self.asked.get(1).planJson(), "round 2 reviews the repaired plan");
        Fake quick = new Fake().repairs(PlanWriter.write(REPAIRED));
        run(quick, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.SUPPORT), 0);
        assertEquals(List.of(PromptSpec.Kind.REVIEW), quick.kinds());
        assertEquals(List.of("REVIEW 1/1"), quick.stages);
        Fake idle = new Fake();
        run(idle, ReviewRounds.Mode.SEPARATE, evidence(), 0);
        assertTrue(idle.asked.isEmpty());
    }

    @Test
    void routeFindingsAloneNeverAskForARepair() throws Exception {
        PlanningFeedback routes = evidence(PlanningFeedback.Kind.ROUTE_START, PlanningFeedback.Kind.UNREACHABLE);
        Fake quick = new Fake();
        run(quick, ReviewRounds.Mode.SEPARATE, routes, 0);
        assertTrue(quick.asked.isEmpty(), "When needed has nothing to correct");
        Fake unanswered = new Fake().critiques("not a critique");
        run(unanswered, ReviewRounds.Mode.SEPARATE, routes, 1);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE), unanswered.kinds(), "without a critique only correctable checks repair");
        Fake satisfied = new Fake().critiques(OK);
        run(satisfied, ReviewRounds.Mode.SEPARATE, routes, 1);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE), satisfied.kinds());
    }

    @Test
    void theSeparateReviewerRunsOnlyWhenOneIsChosen() {
        assertEquals(ReviewRounds.Mode.SELF, ReviewRounds.mode(false, false, false),
                "the server's own AI with no reviewer profile, or one that is missing or can't plan");
        assertEquals(ReviewRounds.Mode.SEPARATE, ReviewRounds.mode(false, false, true), "a usable reviewer profile");
        assertEquals(ReviewRounds.Mode.SELF, ReviewRounds.mode(false, true, false),
                "the server's own AI does not go by a player's game");
        assertEquals(ReviewRounds.Mode.SEPARATE, ReviewRounds.mode(true, true, false), "the player's game has a reviewer");
        assertEquals(ReviewRounds.Mode.SELF, ReviewRounds.mode(true, false, true),
                "a relayed request goes by the player's game, not the server's reviewer profile");
    }

    @Test
    void cancellationStopsEverything() {
        Fake fake = new Fake();
        fake.failure = new CancelledException();
        assertThrows(CancelledException.class, () -> run(fake, 2));
        Fake repairing = new Fake().critiques(SHOULD);
        repairing.repairFailure = new CancelledException();
        assertThrows(CancelledException.class, () -> run(repairing, 2));
        Fake comparing = new Fake().critiques(SHOULD).repairs(PlanWriter.write(REPAIRED));
        comparing.compareFailure = new CancelledException();
        assertThrows(CancelledException.class, () -> run(comparing, 2));
    }
}
