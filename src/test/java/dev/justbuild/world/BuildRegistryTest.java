package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import java.util.HashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuildRegistryTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    static final BuildRegistry.Worlds AIR_WORLD = new BuildRegistry.Worlds() {
        @Override
        public LevelHeightAccessor height(net.minecraft.resources.ResourceKey<Level> dimension) {
            return LevelHeightAccessor.create(-64, 384);
        }

        @Override
        public net.minecraft.world.level.block.state.BlockState live(net.minecraft.resources.ResourceKey<Level> dimension,
                BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }
    };

    static BuildRecord record(String id, String owner, BlockPos origin, long time, int width) {
        BuildPlan plan = new BuildPlan(id, "", List.of(Operation.fill("stone", new Cell(0, 0, 0), new Cell(width - 1, 0, 0), false)));
        return new BuildRecord(id, owner, Level.OVERWORLD, origin, Direction.NORTH,
                List.of(new BuildRecord.Version("r", plan, time)), new HashMap<>());
    }

    @Test
    void placementsPerVersionAndEmptyForZero() {
        BuildRegistry registry = new BuildRegistry(AIR_WORLD);
        BuildRecord r = record("a", "p", new BlockPos(0, 64, 0), 1, 3);
        registry.add(r);
        assertEquals(3, registry.placements(r, 1).size());
        assertTrue(registry.placements(r, 0).isEmpty());
    }

    @Test
    void findsNewestBuildAtPositionThenLatestForOwner() {
        BuildRegistry registry = new BuildRegistry(AIR_WORLD);
        registry.add(record("old", "p", new BlockPos(0, 64, 0), 1, 5));
        registry.add(record("new", "p", new BlockPos(2, 64, 0), 2, 1));
        registry.add(record("other", "q", new BlockPos(100, 64, 0), 3, 1));
        assertEquals("new", registry.at(Level.OVERWORLD, new BlockPos(2, 64, 0)).orElseThrow().id());
        assertEquals("old", registry.at(Level.OVERWORLD, new BlockPos(4, 64, 0)).orElseThrow().id());
        assertTrue(registry.at(Level.OVERWORLD, new BlockPos(50, 64, 0)).isEmpty());
        assertTrue(registry.at(Level.NETHER, new BlockPos(2, 64, 0)).isEmpty());
        assertEquals("new", registry.latest("p", Level.OVERWORLD).orElseThrow().id());
        assertTrue(registry.latest("p", Level.NETHER).isEmpty());
    }

    @Test
    void identifiesCurrentBlocksExcludedByAResizedZone() {
        BuildRegistry registry = new BuildRegistry(AIR_WORLD);
        BlockPos inside = new BlockPos(0, 64, 0), outside = inside.east(2);
        BuildRecord r = record("house", "p", inside, 1, 3);
        r.recordBaseline(List.of(new Placement(inside, Blocks.AIR.defaultBlockState()),
                new Placement(outside, Blocks.AIR.defaultBlockState())));
        r.zone(BuildZone.between(Level.OVERWORLD, inside, outside));
        registry.add(r);
        assertEquals(r, registry.at(Level.OVERWORLD, outside).orElseThrow());
        assertTrue(registry.atOutsideZone(Level.OVERWORLD, outside).isEmpty());

        r.zone(BuildZone.between(Level.OVERWORLD, inside, inside));
        assertTrue(registry.at(Level.OVERWORLD, outside).isEmpty());
        assertEquals(r, registry.atOutsideZone(Level.OVERWORLD, outside).orElseThrow());
        assertEquals(r, registry.at(Level.OVERWORLD, inside).orElseThrow());
        assertTrue(registry.atOutsideZone(Level.OVERWORLD, inside).isEmpty());
        assertTrue(registry.atOutsideZone(Level.NETHER, outside).isEmpty());
        assertTrue(registry.atOutsideZone(Level.OVERWORLD, outside.east()).isEmpty());
        assertTrue(registry.atOutsideZone(Level.OVERWORLD, inside.east()).isEmpty(),
                "a planned block that was never placed is not evidence of a saved build");

        r.addVersion(new BuildRecord.Version("remove extension", new BuildPlan("house", "",
                List.of(Operation.set("stone", new Cell(0, 0, 0)))), 2));
        assertTrue(registry.atOutsideZone(Level.OVERWORLD, outside).isEmpty(),
                "historical positions no longer in the current build must not be reported as current blocks");
    }

    @Test
    void cacheIsRecomputedWhenTheBaselineGrows() {
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world = new java.util.HashMap<>();
        BuildRegistry.Worlds live = new BuildRegistry.Worlds() {
            @Override
            public LevelHeightAccessor height(net.minecraft.resources.ResourceKey<Level> dimension) {
                return LevelHeightAccessor.create(-64, 384);
            }

            @Override
            public net.minecraft.world.level.block.state.BlockState live(
                    net.minecraft.resources.ResourceKey<Level> dimension, BlockPos pos) {
                return world.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            }
        };
        BuildRegistry registry = new BuildRegistry(live);
        BuildPlan plan = new BuildPlan("p", "", List.of(Operation.replace("gold_block", "stone", new Cell(0, 0, 0), new Cell(0, 0, 0))));
        BuildRecord r = new BuildRecord("r", "p", Level.OVERWORLD, new BlockPos(0, 64, 0), Direction.NORTH,
                List.of(new BuildRecord.Version("x", plan, 1)), new HashMap<>());
        registry.add(r);
        // Mid-build the world already shows the build's own gold, so the replace target is not found...
        world.put(new BlockPos(0, 64, 0), Blocks.GOLD_BLOCK.defaultBlockState());
        assertTrue(registry.current(r).isEmpty());
        // ...until the job records what was there before (stone); the cached map must be recomputed.
        r.recordBaseline(List.of(new Placement(new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState())));
        assertEquals(1, registry.current(r).size());
    }

    @Test
    void cacheIsInvalidatedWhenVersionsChange() {
        BuildRegistry registry = new BuildRegistry(AIR_WORLD);
        BuildRecord r = record("a", "p", new BlockPos(0, 64, 0), 1, 3);
        registry.add(r);
        assertEquals(3, registry.current(r).size());
        BuildRegion bounds = registry.bounds(r);
        assertEquals(new BlockPos(2, 64, 0), bounds.max());
        assertSame(bounds, registry.bounds(r));
        r.addVersion(new BuildRecord.Version("wider", new BuildPlan("a", "", List.of(
                Operation.fill("stone", new Cell(0, 0, 0), new Cell(5, 0, 0), false))), 5));
        assertEquals(6, registry.current(r).size());
        assertEquals(new BlockPos(5, 64, 0), registry.bounds(r).max());
        r.removeLastVersion();
        assertEquals(3, registry.current(r).size());
        assertEquals(bounds, registry.bounds(r));
    }

    @Test
    void blocksComputedFromUnloadedChunksAreNotCached() {
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> world = new java.util.HashMap<>();
        boolean[] loaded = {false};
        BuildRegistry.Worlds live = new BuildRegistry.Worlds() {
            @Override
            public LevelHeightAccessor height(net.minecraft.resources.ResourceKey<Level> dimension) {
                return LevelHeightAccessor.create(-64, 384);
            }

            @Override
            public net.minecraft.world.level.block.state.BlockState live(
                    net.minecraft.resources.ResourceKey<Level> dimension, BlockPos pos) {
                return loaded[0] ? world.getOrDefault(pos, Blocks.AIR.defaultBlockState()) : null;
            }
        };
        BuildRegistry registry = new BuildRegistry(live);
        BuildPlan plan = new BuildPlan("p", "", List.of(Operation.replace("gold_block", "stone", new Cell(0, 0, 0), new Cell(0, 0, 0))));
        BuildRecord r = new BuildRecord("r", "p", Level.OVERWORLD, new BlockPos(0, 64, 0), Direction.NORTH,
                List.of(new BuildRecord.Version("x", plan, 1)), new HashMap<>());
        registry.add(r);
        world.put(new BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState());
        assertTrue(registry.current(r).isEmpty(), "unloaded: the stone can't be seen");
        loaded[0] = true;
        assertEquals(1, registry.current(r).size(), "once loaded, the blocks are computed again");
    }
}
