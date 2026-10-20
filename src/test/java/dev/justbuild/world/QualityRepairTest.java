package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.PlanException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QualityRepairTest {
    @BeforeAll static void bootstrap() { McBootstrap.init(); }

    /** Builds above the old fixed 100,000 limit are checked and pictured; maxBlocks is what bounds them now. */
    @Test void buildsBeyondOneHundredThousandBlocksAreStillCheckedAndPictured() {
        var cells = new java.util.HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (int i = 0; i < 150_000; i++) cells.put(new BlockPos(i % 96, i / 9216, (i / 96) % 96), Blocks.STONE.defaultBlockState());
        assertTrue(BuildQuality.details(cells, p -> Blocks.AIR.defaultBlockState()).stream()
                .noneMatch(issue -> issue.kind() == BuildQuality.Kind.LIMIT));
        BuildPlan big = new BuildPlan("Block", "", List.of(Operation.fill("stone", new Cell(0, 0, 0), new Cell(95, 16, 95), false)));
        assertNotEquals(DesignThumbnail.Image.EMPTY, DesignThumbnail.create(big), "a 156,672-block plan should get a thumbnail");
    }
    @Test void doorwayCandidateOnlyClearsTheBlockedApproachAndDoesNotMutateInput() throws Exception {
        var door=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.NORTH);
        var wall=Blocks.STONE.defaultBlockState(); var cells=Map.of(BlockPos.ZERO,door,BlockPos.ZERO.north(),wall,BlockPos.ZERO.north().above(),wall);
        var issue=BuildQuality.details(cells,p->p.getY()<0?wall:Blocks.AIR.defaultBlockState()).stream()
                .filter(i->i.kind()==BuildQuality.Kind.DOOR).findFirst().orElseThrow();
        var repair=BuildQuality.repair(issue,cells,p->Blocks.AIR.defaultBlockState());
        assertEquals(2,repair.size()); assertTrue(repair.values().stream().allMatch(s->s.isAir()));
        assertTrue(cells.get(BlockPos.ZERO.north()).is(Blocks.STONE));
    }
    @Test void supportCandidateReachesSolidGroundAndRejectsLongOrObstructedDrops() throws Exception {
        var cells=Map.of(new BlockPos(0,4,0),Blocks.STONE.defaultBlockState());
        var issue=BuildQuality.details(cells,p->p.getY()==0?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState()).getFirst();
        assertEquals(BuildQuality.Kind.SUPPORT,issue.kind());
        var repair=BuildQuality.repair(issue,cells,p->p.getY()==0?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        assertEquals(3,repair.size()); assertTrue(repair.containsKey(new BlockPos(0,1,0)));
        assertThrows(PlanException.class,()->BuildQuality.repair(issue,cells,p->Blocks.AIR.defaultBlockState()));
        assertThrows(PlanException.class,()->BuildQuality.repair(issue,cells,p->Blocks.WATER.defaultBlockState()));
    }

    @Test void borderStripNeedsAFullWidthLandingAtTheFinalStepHeight() throws Exception {
        // Cluckotron's two steps end at z=13/y=1, but its raised platform starts at z=11/y=1.
        // The lower border at z=12/y=0 needs a landing on top of it, not another step or a deeper air cut.
        List<Operation> original = List.of(
                Operation.fill("polished_deepslate", new Cell(-3, 0, 10), new Cell(3, 0, 12), false),
                Operation.fill("smooth_stone", new Cell(-3, 1, 10), new Cell(3, 1, 11), false),
                Operation.stairs("polished_deepslate_stairs", "polished_deepslate", new Cell(-2, 0, 14),
                        new Cell(2, 0, 14), "north", 2));
        List<Operation> connected = new ArrayList<>(original);
        connected.add(Operation.fill("smooth_stone", new Cell(-2, 1, 12), new Cell(2, 1, 12), false));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var ground = (PlanPipeline.WorldLookup) p -> p.getY() < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            var broken = PlanPipeline.run(new BuildPlan("Bordered platform", "", original), BlockPos.ZERO, facing,
                    LevelHeightAccessor.create(-64, 384), ground, 1000).placements();
            assertTrue(BuildQuality.inspect(broken, ground::get).stream().anyMatch(s -> s.startsWith("5 stair(s)")), facing.getName());
            var fixed = PlanPipeline.run(new BuildPlan("Connected platform", "", connected), BlockPos.ZERO, facing,
                    LevelHeightAccessor.create(-64, 384), ground, 1000).placements();
            assertTrue(BuildQuality.details(fixed, ground::get).stream().noneMatch(i -> i.kind() == BuildQuality.Kind.STAIR),
                    facing.getName());
            assertEquals(broken.size() + 5, fixed.size());
        }
    }
}
