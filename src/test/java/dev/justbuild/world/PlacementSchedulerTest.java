package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlacementSchedulerTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    static class MapWorld implements BlockAccess {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        int writes;
        int refreshes;

        @Override
        public void refresh(BlockPos pos) {
            refreshes++;
        }

        @Override
        public BlockState get(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean set(BlockPos pos, BlockState state) {
            blocks.put(pos, state);
            writes++;
            return true;
        }
    }

    static List<Placement> stones(int n) {
        List<Placement> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Placement(new BlockPos(i, 0, 0), Blocks.STONE.defaultBlockState()));
        }
        return list;
    }

    @Test
    void placesWithinBudgetAndRecordsPreviousStates() {
        MapWorld world = new MapWorld();
        world.blocks.put(new BlockPos(1, 0, 0), Blocks.DIRT.defaultBlockState());
        List<PlacementJob> finished = new ArrayList<>();
        PlacementScheduler scheduler = new PlacementScheduler(() -> 2, finished::add);
        PlacementJob job = new PlacementJob("p1", PlacementJob.Kind.BUILD, "Wall", world, stones(3));
        scheduler.enqueue(job);

        scheduler.tick();
        assertEquals(2, job.placedCount());
        assertTrue(finished.isEmpty());
        scheduler.tick();
        assertEquals(3, job.placedCount());
        assertTrue(finished.isEmpty(), "the refresh pass still has to run");
        assertEquals(1, world.refreshes);
        scheduler.tick();
        assertEquals(List.of(job), finished);
        assertEquals(3, world.refreshes);
        assertTrue(world.get(new BlockPos(2, 0, 0)).is(Blocks.STONE));
        assertTrue(job.previousStates().get(1).state().is(Blocks.DIRT));
        assertTrue(job.previousStates().get(0).state().isAir());
    }

    @Test
    void skipsWritesThatChangeNothing() {
        MapWorld world = new MapWorld();
        world.blocks.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        PlacementJob job = new PlacementJob("p1", PlacementJob.Kind.BUILD, "t", world, stones(1));
        job.step(10);
        assertEquals(0, world.writes);
        assertTrue(job.done());
    }

    @Test
    void cancelRemovesOnlyThatOwnersJobs() {
        MapWorld world = new MapWorld();
        PlacementScheduler scheduler = new PlacementScheduler(() -> 1, job -> { });
        PlacementJob a = new PlacementJob("a", PlacementJob.Kind.BUILD, "A", world, stones(5));
        PlacementJob b = new PlacementJob("b", PlacementJob.Kind.BUILD, "B", world, stones(5));
        scheduler.enqueue(a);
        scheduler.enqueue(b);
        scheduler.tick();
        assertTrue(scheduler.busy("a"));
        assertEquals(List.of(a), scheduler.cancel("a"));
        assertFalse(scheduler.busy("a"));
        assertEquals(b, scheduler.active("b").orElseThrow());
        scheduler.tick();
        assertEquals(1, b.placedCount());
    }

    @Test
    void jobsWaitForUnloadedChunksWhileOthersGoOn() {
        java.util.Set<BlockPos> unloaded = new java.util.HashSet<>(List.of(new BlockPos(0, 0, 0)));
        MapWorld away = new MapWorld() {
            @Override
            public boolean loaded(BlockPos pos) {
                return !unloaded.contains(pos);
            }
        };
        MapWorld here = new MapWorld();
        List<PlacementJob> finished = new ArrayList<>();
        PlacementScheduler scheduler = new PlacementScheduler(() -> 10, finished::add);
        PlacementJob waiting = new PlacementJob("a", PlacementJob.Kind.BUILD, "Away", away, stones(2));
        PlacementJob next = new PlacementJob("b", PlacementJob.Kind.BUILD, "Here", here, stones(2));
        scheduler.enqueue(waiting);
        scheduler.enqueue(next);

        scheduler.tick();
        assertEquals(0, away.writes, "nothing is placed (or loaded) where the chunk is unloaded");
        assertTrue(waiting.waiting());
        assertEquals(2, here.writes, "another player's job is not held up");
        unloaded.clear();
        scheduler.tick();
        scheduler.tick();
        assertFalse(waiting.waiting());
        assertEquals(2, away.writes);
        assertTrue(finished.contains(waiting) && finished.contains(next));
    }
}
