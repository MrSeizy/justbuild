package dev.justbuild.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Critique;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanLimits;
import dev.justbuild.plan.PlanWriter;
import dev.justbuild.plan.RepairOutcome;
import dev.justbuild.prompt.PlanningFeedback;
import dev.justbuild.provider.CancelledException;
import dev.justbuild.provider.ProviderException;
import dev.justbuild.relay.PromptCheck;
import dev.justbuild.relay.PromptSpec;
import dev.justbuild.world.ReviewCheck;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ReviewRoundsTest {
    static final BuildPlan DRAFT = new BuildPlan("Hut", "A hut", List.of(Operation.set("minecraft:stone", new Cell(0, 0, 0))));
    static final BuildPlan REPAIRED = new BuildPlan("Hut", "A hut", List.of(Operation.set("minecraft:stone", new Cell(0, 0, 0)),
            Operation.set("minecraft:oak_planks", new Cell(0, 1, 0))));
    static final String OK = "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":[],\"keep\":[]}";
    static final String MINOR = "{\"kind\":\"critique\",\"verdict\":\"ok\",\"problems\":[{\"severity\":\"minor\",\"category\":\"facade\","
            + "\"operations\":[0],\"cell\":null,\"problem\":\"Plain wall.\",\"fix\":\"Add a window.\"}],\"keep\":[]}";
    static final String SHOULD = MINOR.replace("\"minor\"", "\"should_fix\"").replace("\"ok\"", "\"revise\"");
    static final PlanLimits LIMITS = new PlanLimits(2000, 250000, 96);
    static final PromptSpec BASE = PromptSpec.newBuild("minecraft:overworld", "minecraft:plains", PromptSpec.Placement.FRONT,
            null, null, "a hut", List.of(), 0, null, 250000);

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

        Fake critiques(String... answers) { critiques.addAll(List.of(answers)); return this; }
        Fake repairs(String... answers) { repairs.addAll(List.of(answers)); return this; }

        @Override public String critique(PromptSpec spec) throws ProviderException {
            asked.add(spec);
            if (failure != null) throw failure;
            String next = critiques.poll();
            if (next == null) throw new ProviderException("the reviewer did not answer");
            return next;
        }
        @Override public String repair(PromptSpec spec) {
            asked.add(spec);
            return Objects.requireNonNull(repairs.poll(), "unexpected repair call");
        }
        @Override public ReviewRounds.Compared compare(BuildPlan draft, BuildPlan repaired) {
            return new ReviewRounds.Compared(verdict, PlanningFeedback.EMPTY);
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
        assertFalse(result.rounds().get(0).repaired());
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
    }

    @Test
    void checksThatNeedCorrectionRepairEvenWhenTheReviewerIsSatisfied() throws Exception {
        Fake fake = new Fake().critiques(OK).repairs(PlanWriter.write(REPAIRED));
        run(fake, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.SUPPORT), 1);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW), fake.kinds());
        assertNull(fake.asked.get(1).critique(), "nothing from an ok critique is applied");
        assertEquals("Fixing the reported problems…", fake.lines.get(0));
    }

    @Test
    void aSatisfiedCritiqueIsNotCitedAfterAChecksOnlyRepairChangedThePlan() throws Exception {
        Fake fake = new Fake().critiques(OK, OK).repairs(PlanWriter.write(REPAIRED));
        run(fake, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.SUPPORT), 2);
        assertEquals(List.of(PromptSpec.Kind.CRITIQUE, PromptSpec.Kind.REVIEW, PromptSpec.Kind.CRITIQUE), fake.kinds());
        assertNull(fake.asked.get(2).critique(), "the ok critique is about the old plan and no repair carried it");
        assertEquals(PlanWriter.write(REPAIRED), fake.asked.get(2).planJson());
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
        Fake quick = new Fake().repairs(PlanWriter.write(REPAIRED));
        run(quick, ReviewRounds.Mode.SEPARATE, evidence(PlanningFeedback.Kind.SUPPORT), 0);
        assertEquals(List.of(PromptSpec.Kind.REVIEW), quick.kinds());
        Fake idle = new Fake();
        run(idle, ReviewRounds.Mode.SEPARATE, evidence(), 0);
        assertTrue(idle.asked.isEmpty());
    }

    @Test
    void cancellationStopsEverything() {
        Fake fake = new Fake();
        fake.failure = new CancelledException();
        assertThrows(CancelledException.class, () -> run(fake, 2));
    }
}
