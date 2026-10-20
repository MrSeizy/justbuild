package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;
import dev.justbuild.plan.*;
import dev.justbuild.prompt.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReviewCheckTest {
    private static final PlanLimits LIMITS = new PlanLimits(100, 2500, 32);
    private static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);
    private static final Function<BlockPos, BlockState> GROUND = p -> p.getY() < 0
            ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
    @BeforeAll static void bootstrap() { McBootstrap.init(); }

    @Test void rejectsAllFiveRecordedShellRemovalReviews() throws Exception {
        Map<BlockPos, BlockState> source = placements(fixture("source"));
        for (String name : List.of("claude-before-interior-1", "codex-before-interior-1", "codex-before-interior-2",
                "codex-after-interior-1", "codex-after-interior-2")) {
            var verdict = compare(fixture(name + "-draft"), fixture(name + "-review"), source);
            assertFalse(verdict.accepted(), name);
            assertTrue(verdict.reason().contains("existing structure"), verdict.reason());
        }
    }

    @Test void acceptsUnchangedDraftAndMaterialReplacement() throws Exception {
        var shell = fixture("source"); var source = placements(shell);
        assertTrue(compare(shell, shell, source).accepted());
        var brick = add(shell, Operation.replace("bricks", "oak_planks", new Cell(0, 0, 0), new Cell(8, 8, 6)));
        assertTrue(compare(shell, brick, source).accepted());
    }

    @Test void requestedInitialRemovalIsAllowedButFurtherReviewDemolitionIsNot() throws Exception {
        var shell = fixture("source"); var source = placements(shell);
        var opened = add(shell, Operation.set("air", new Cell(0, 2, 3)));
        var lit = add(opened, Operation.set("lantern", new Cell(3, 1, 3)));
        assertTrue(compare(opened, lit, source).accepted(), "The initial edit's requested opening must remain allowed");
        var demolished = add(lit, Operation.fill("air", new Cell(0, 1, 1), new Cell(0, 3, 5), false));
        assertFalse(compare(opened, demolished, source).accepted(), "Explicit air must not bypass preservation");
    }

    @Test void rejectsNewBlockedEntranceAndDisconnectedRoomWithoutExistingBuildAnchors() throws Exception {
        var shell = fixture("source");
        // Both proposals are new builds. The route checks must work independently of source preservation.
        var blocked = add(shell, Operation.fill("stone", new Cell(4, 1, 1), new Cell(4, 3, 1), false));
        var verdict = compare(shell, blocked, Map.of());
        assertFalse(verdict.accepted());
        assertTrue(verdict.reason().contains("floor space") || verdict.reason().contains("doorway"), verdict.reason());
        var partitioned = add(shell, Operation.fill("stone", new Cell(1, 1, 3), new Cell(7, 3, 3), false));
        assertFalse(compare(shell, partitioned, Map.of()).accepted());
        var furniture = add(shell, Operation.set("crafting_table", new Cell(1, 1, 1)));
        assertTrue(compare(shell, furniture, Map.of()).accepted(), "A furnishing may occupy previously open floor space");
    }

    @Test void acceptsAUsefulFloatingDraftRepairAndRejectsSwappedTechnicalFailures() throws Exception {
        var floating = new BuildPlan("Pad", "", List.of(Operation.fill("stone", new Cell(-3, 2, -3), new Cell(3, 2, 3), false)));
        var supported = new BuildPlan("Pad", "", List.of(Operation.fill("stone", new Cell(-3, 0, -3), new Cell(3, 0, 3), false)));
        assertTrue(compare(floating, supported, Map.of()).accepted());
        var invalid = new BuildPlan("Bad", "", List.of(Operation.set("minecraft:does_not_exist", new Cell(0, 0, 0))));
        assertTrue(compare(invalid, supported, Map.of()).accepted());
        var huge = new BuildPlan("Huge", "", List.of(Operation.fill("stone", new Cell(0, 0, 0), new Cell(64, 64, 64), false)));
        assertFalse(compare(invalid, huge, Map.of()).accepted(), "Fixing one technical category cannot pay for another");
    }

    @Test void fixingAnInvalidBlockMustNotAllowShellRemoval() throws Exception {
        var shell = fixture("source"); var source = placements(shell);
        var invalid = add(shell, Operation.set("minecraft:does_not_exist", new Cell(3, 1, 3)));
        assertTrue(compare(invalid, shell, source).accepted(), "A repair that preserves the shell remains valid");
        var furnitureOnly = new BuildPlan("Furniture", "", List.of(Operation.set("crafting_table", new Cell(3, 1, 3))));
        var verdict = compare(invalid, furnitureOnly, source);
        assertFalse(verdict.accepted(), "Repairing an invalid block cannot pay for removing the room");
        assertTrue(verdict.reason().contains("existing structure"), verdict.reason());
    }

    @Test void furnishingADoorlessFallbackStartKeepsTheRemainingRouteConnected() throws Exception {
        var platform = new BuildPlan("Raised workshop", "", List.of(
                Operation.fill("stone", new Cell(0, 2, 0), new Cell(4, 2, 4), false),
                Operation.fill("oak_log", new Cell(4, 3, 4), new Cell(4, 5, 4), false)));
        var furnished = add(platform, Operation.set("crafting_table", new Cell(0, 3, 0)));
        assertTrue(compare(platform, furnished, Map.of()).accepted(), "A fallback start is not a required doorway");
        var divided = add(furnished, Operation.fill("stone", new Cell(2, 3, 0), new Cell(2, 5, 4), false));
        assertFalse(compare(platform, divided, Map.of()).accepted(), "Re-anchoring must still detect a split route");
    }

    @Test void unknownOrOversizedRouteGraphsDoNotClaimARegression() {
        var empty = new PlanningCheck.Result(PlanningFeedback.EMPTY, Map.of());
        var far = new PlanningCheck.Result(PlanningFeedback.EMPTY,
                Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), new BlockPos(500, 500, 500), Blocks.STONE.defaultBlockState()));
        assertTrue(ReviewCheck.compare(empty, far, Map.of(), Level.OVERWORLD, GROUND).accepted());
        var near = new PlanningCheck.Result(PlanningFeedback.EMPTY, Map.of(new BlockPos(4, 3, 4), Blocks.STONE.defaultBlockState()));
        assertTrue(ReviewCheck.compare(empty, near, Map.of(), Level.OVERWORLD, p -> null).accepted());
    }

    private static ReviewCheck.Verdict compare(BuildPlan draft, BuildPlan revised, Map<BlockPos, BlockState> source) {
        Function<BlockPos, BlockState> live = p -> source.getOrDefault(p, GROUND.apply(p));
        Map<BlockPos, BlockState> baseline = new HashMap<>();
        source.keySet().forEach(p -> baseline.put(p, GROUND.apply(p)));
        var before = PlanningCheck.check(draft, BlockPos.ZERO, Direction.NORTH, HEIGHT, live,
                baseline, source, !source.isEmpty(), p -> true, null, BuildConstraints.NONE, LIMITS);
        var after = PlanningCheck.check(revised, BlockPos.ZERO, Direction.NORTH, HEIGHT, live,
                baseline, source, !source.isEmpty(), p -> true, null, BuildConstraints.NONE, LIMITS);
        return ReviewCheck.compare(before, after, source, Level.OVERWORLD, live);
    }

    private static BuildPlan add(BuildPlan plan, Operation operation) {
        var operations = new ArrayList<>(plan.operations()); operations.add(operation);
        return new BuildPlan(plan.title(), plan.summary(), operations);
    }
    private static Map<BlockPos, BlockState> placements(BuildPlan plan) throws PlanException {
        return PlanPipeline.run(plan, BlockPos.ZERO, Direction.NORTH, HEIGHT, GROUND::apply, LIMITS.maxBlocks()).placements();
    }
    private static BuildPlan fixture(String name) throws Exception {
        try (var input = ReviewCheckTest.class.getResourceAsStream("/review-regressions/" + name + ".json")) {
            assertNotNull(input, name);
            return ((PlanResponse.Build) PlanParser.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))).plan();
        }
    }
}
