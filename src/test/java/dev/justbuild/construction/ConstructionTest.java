package dev.justbuild.construction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static net.minecraft.world.level.block.state.properties.BlockStateProperties.*;

class ConstructionTest {
    @BeforeAll static void bootstrap() { dev.justbuild.world.McBootstrap.init(); }
    @TempDir Path directory;

    @Test void followsCompleteWallCircuitsBottomUpRegardlessOfInputOrder() {
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 0; y < 3; y++) for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++)
            if (x == 0 || x == 6 || z == 0 || z == 6) cells.add(new BlockPos(x, y, z));
        List<BlockPos> ordered = CourseOrder.order(cells); Collections.shuffle(cells, new Random(12));
        assertEquals(ordered, CourseOrder.order(cells)); assertEquals(72, new HashSet<>(ordered).size());
        for (int i = 1; i < ordered.size(); i++) assertEquals(1, ordered.get(i).distManhattan(ordered.get(i - 1)));
        assertEquals(new BlockPos(0, 0, 0), ordered.getFirst());
        assertEquals(new BlockPos(6, 0, 0), ordered.get(6)); assertEquals(new BlockPos(6, 0, 6), ordered.get(12));
    }
    @Test void sparseBlueprintOrderingIsBoundedAndDoesNotLoseCells() {
        List<BlockPos> cells = new ArrayList<>(); for (int i = 0; i < 10_000; i++) cells.add(new BlockPos(i * 3, 2, i % 20 * 3));
        assertTimeout(Duration.ofSeconds(5), () -> assertEquals(new HashSet<>(cells), new HashSet<>(CourseOrder.order(cells))));
    }
    @Test void interiorAirDoesNotSteerWallsAndAttachedDetailsFollowTheirSupports() {
        Map<BlockPos, BlockState> cells = new HashMap<>(); List<BlockPos> walls = new ArrayList<>();
        for (int y = 0; y < 3; y++) for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
            BlockPos pos = new BlockPos(x, y, z); boolean wall = x == 0 || x == 6 || z == 0 || z == 6;
            cells.put(pos, wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState()); if (wall) walls.add(pos);
        }
        var project = new ConstructionProject(UUID.randomUUID(), UUID.randomUUID(), "Hollow walls", Level.OVERWORLD, cells, ConstructionProject.Mode.COBBLE);
        assertEquals(72, project.totalBlocks);
        assertEquals(CourseOrder.order(walls), project.order.stream().filter(p -> !cells.get(p).isAir()).toList());
        BlockPos torch = new BlockPos(3, 0, -1); cells.put(torch, Blocks.WALL_TORCH.defaultBlockState());
        var details = new ConstructionProject(UUID.randomUUID(), UUID.randomUUID(), "Torch wall", Level.OVERWORLD, cells, ConstructionProject.Mode.COBBLE);
        assertTrue(details.order.indexOf(torch) > details.order.indexOf(new BlockPos(3, 0, 0)));
        assertTrue(details.order.indexOf(torch) < details.order.indexOf(new BlockPos(3, 1, 0)));
    }
    @Test void helperRoutesAroundObstaclesWithoutTeleportingOrLeavingItsBounds() {
        BlockPos start = BlockPos.ZERO, goal = new BlockPos(4, 0, 0);
        List<BlockPos> path = ConstructionPath.find(start, goal, new BlockPos(0, 0, 0), new BlockPos(4, 0, 3),
                p -> p.getX() != 2 || p.getZ() == 3);
        assertFalse(path.isEmpty()); assertEquals(start, path.getFirst()); assertEquals(goal, path.getLast());
        assertTrue(path.contains(new BlockPos(2, 0, 3)));
        for (int i = 1; i < path.size(); i++) assertEquals(1, path.get(i).distManhattan(path.get(i - 1)));
        assertTrue(ConstructionPath.find(start, goal, start, goal, p -> p.getX() != 2).isEmpty());
        var climb = ConstructionPath.find(start, start.above(4), start, start.above(4), p -> true);
        assertEquals(5, climb.size());
    }
    @Test void doorAndBedConsumeOneItemForBothHalves() {
        BlockPos at = BlockPos.ZERO; Map<BlockPos, BlockState> door = Map.of(at, Blocks.OAK_DOOR.defaultBlockState(),
                at.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        var unit = ConstructionMaterials.unit(door, at.above());
        assertEquals(at, unit.anchor()); assertEquals(2, unit.cells().size()); assertEquals(Items.OAK_DOOR, unit.item());
        assertEquals(1, unit.count()); assertNull(unit.manual());
        var foot = dev.justbuild.world.BlockResolver.parse("red_bed[facing=east,part=foot]").orElseThrow();
        var bed = Map.of(at, foot, at.east(), foot.setValue(BED_PART, BedPart.HEAD));
        var head = ConstructionMaterials.unit(bed, at.east());
        assertEquals(at, head.anchor()); assertEquals(1, head.count()); assertEquals(foot.getBlock().asItem(), head.item()); assertNull(head.manual());
        assertNotNull(ConstructionMaterials.unit(Map.of(at, foot), at).manual());
    }
    @Test void stackedBlocksPayFullCostAndGrowthFluidsRemainManual() {
        assertCost(Blocks.OAK_SLAB.defaultBlockState().setValue(SLAB_TYPE, SlabType.DOUBLE), Items.OAK_SLAB, 2);
        assertCost(Blocks.SNOW.defaultBlockState().setValue(LAYERS, 7), Items.SNOW, 7);
        assertCost(Blocks.CANDLE.defaultBlockState().setValue(CANDLES, 4), Items.CANDLE, 4);
        assertCost(Blocks.PINK_PETALS.defaultBlockState().setValue(FLOWER_AMOUNT, 4), Items.PINK_PETALS, 4);
        assertCost(Blocks.LEAF_LITTER.defaultBlockState().setValue(SEGMENT_AMOUNT, 4), Items.LEAF_LITTER, 4);
        assertCost(Blocks.CAULDRON.defaultBlockState(), Items.CAULDRON, 1);
        // The cauldron item stands for every filled cauldron; a lava cauldron has no level to charge for.
        for (BlockState state : List.of(Blocks.WATER.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState().setValue(WATERLOGGED, true),
                Blocks.WHEAT.defaultBlockState().setValue(AGE_7, 7), Blocks.TNT.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                Blocks.LAVA_CAULDRON.defaultBlockState(), Blocks.WATER_CAULDRON.defaultBlockState(), Blocks.POWDER_SNOW_CAULDRON.defaultBlockState()))
            assertNotNull(ConstructionMaterials.unit(Map.of(BlockPos.ZERO, state), BlockPos.ZERO).manual(), state.toString());
    }
    @Test void remainingMaterialsSeparateCoursesAndCountPairedAndStackedPurchases() {
        BlockPos at = BlockPos.ZERO;
        Map<BlockPos, BlockState> blocks = Map.of(
                at, Blocks.STONE.defaultBlockState(), at.east(), Blocks.STONE.defaultBlockState(),
                at.north(), Blocks.OAK_PLANKS.defaultBlockState(),
                at.north(2), Blocks.OAK_SLAB.defaultBlockState().setValue(SLAB_TYPE, SlabType.DOUBLE),
                at.west(), Blocks.OAK_DOOR.defaultBlockState(),
                at.west().above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                at.above(), Blocks.GLASS.defaultBlockState(), at.east().above(), Blocks.STONE.defaultBlockState(),
                at.south(), Blocks.WATER.defaultBlockState());
        Map<BlockPos, ConstructionMaterials.Unit> units = new HashMap<>();
        blocks.keySet().forEach(pos -> { var unit = ConstructionMaterials.unit(blocks, pos); units.put(unit.anchor(), unit); });
        var empty = ConstructionMaterials.remaining(units.values(), Set.of(), 0);
        assertEquals(Map.of(Items.STONE, 3, Items.OAK_PLANKS, 1, Items.OAK_SLAB, 2, Items.OAK_DOOR, 1, Items.GLASS, 1), empty.total());
        assertEquals(Map.of(Items.STONE, 2, Items.OAK_PLANKS, 1, Items.OAK_SLAB, 2, Items.OAK_DOOR, 1), empty.layer());
        assertEquals(1, empty.manual()); assertEquals(1, empty.layerManual());
        var upper = ConstructionMaterials.remaining(units.values(), Set.of(), 1);
        assertEquals(Map.of(Items.STONE, 1, Items.GLASS, 1), upper.layer(), "The door is bought on its lower course only");
        assertEquals(0, upper.layerManual());
        var placed = ConstructionMaterials.remaining(units.values(), Set.of(at, at.west(), at.west().above()), 0);
        assertEquals(Map.of(Items.STONE, 1, Items.OAK_PLANKS, 1, Items.OAK_SLAB, 2), placed.layer());
        assertEquals(2, placed.total().get(Items.STONE)); assertFalse(placed.total().containsKey(Items.OAK_DOOR));
        var brokenPair = ConstructionMaterials.remaining(units.values(), Set.of(at.west()), 1);
        assertEquals(2, brokenPair.manual()); assertEquals(1, brokenPair.layerManual());
        assertFalse(brokenPair.total().containsKey(Items.OAK_DOOR), "Partial pairs require manual preparation, not another purchase");
        var complete = ConstructionMaterials.remaining(units.values(), blocks.keySet(), 1);
        assertTrue(complete.total().isEmpty()); assertTrue(complete.layer().isEmpty()); assertEquals(0, complete.manual());
    }
    @Test void orphanedAndInconsistentPairsRemainManualAtTheSelectedCell() {
        var upper = Blocks.OAK_DOOR.defaultBlockState().setValue(DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        var unit = ConstructionMaterials.unit(Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), BlockPos.ZERO.above(), upper), BlockPos.ZERO.above());
        assertNotNull(unit.manual()); assertEquals(Set.of(BlockPos.ZERO.above()), unit.cells().keySet());
        var badFacing = upper.setValue(HORIZONTAL_FACING, Direction.EAST);
        assertNotNull(ConstructionMaterials.unit(Map.of(BlockPos.ZERO, Blocks.OAK_DOOR.defaultBlockState(), BlockPos.ZERO.above(), badFacing), BlockPos.ZERO).manual());
    }
    private void assertCost(BlockState state, Item item, int cost) {
        var unit = ConstructionMaterials.unit(Map.of(BlockPos.ZERO, state), BlockPos.ZERO);
        assertEquals(item, unit.item()); assertEquals(cost, unit.count()); assertNull(unit.manual());
    }
    @Test void roundTripsIntentWithRestartPausedAndNoClaimedProgress() throws Exception {
        UUID owner = UUID.randomUUID(); var store = new ConstructionStore(directory);
        var p = new ConstructionProject(UUID.randomUUID(), owner, "Stone house", Level.OVERWORLD,
                Map.of(BlockPos.ZERO, Blocks.OAK_LOG.defaultBlockState().setValue(AXIS, Direction.Axis.X), BlockPos.ZERO.above(), Blocks.AIR.defaultBlockState()),
                ConstructionProject.Mode.COBBLE);
        p.chest = new BlockPos(2, 0, 2); p.currentLayer = false; p.paused = false; store.save(p);
        var restored = store.load(owner); assertEquals(p.id, restored.id); assertEquals(p.blueprint, restored.blueprint);
        assertEquals(p.chest, restored.chest); assertEquals(p.mode, restored.mode); assertFalse(restored.currentLayer); assertTrue(restored.paused);
        store.delete(owner); assertNull(store.load(owner));
    }
    @Test void unknownAndCorruptFormatsArePreservedUntilExplicitRemoval() throws Exception {
        UUID owner = UUID.randomUUID(); var store = new ConstructionStore(directory);
        for (String bad : List.of("{\"format\":2,\"owner\":\"" + owner + "\"}", "not json")) {
            try (var out = new GZIPOutputStream(Files.newOutputStream(store.file(owner)))) { out.write(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            byte[] original = Files.readAllBytes(store.file(owner));
            assertThrows(java.io.IOException.class, () -> store.load(owner));
            var p = new ConstructionProject(UUID.randomUUID(), owner, "Test", Level.OVERWORLD, Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()), ConstructionProject.Mode.GUIDE);
            assertThrows(java.io.IOException.class, () -> store.save(p)); assertArrayEquals(original, Files.readAllBytes(store.file(owner)));
        }
        // Setting the file aside keeps its bytes under a new name and frees the slot for a new project.
        byte[] original = Files.readAllBytes(store.file(owner));
        Path kept = store.setAside(owner);
        assertArrayEquals(original, Files.readAllBytes(kept)); assertNull(store.load(owner));
        var fresh = new ConstructionProject(UUID.randomUUID(), owner, "Fresh", Level.OVERWORLD, Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()), ConstructionProject.Mode.GUIDE);
        store.save(fresh); assertEquals(fresh.id, store.load(owner).id);
    }
    @Test void verifiedProjectsSaveRepeatedlyWithoutReparsing() throws Exception {
        UUID owner = UUID.randomUUID(); var store = new ConstructionStore(directory);
        var p = new ConstructionProject(UUID.randomUUID(), owner, "Settings", Level.OVERWORLD, Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()), ConstructionProject.Mode.GUIDE);
        store.save(p); p.mode = ConstructionProject.Mode.COBBLE; store.save(p); p.currentLayer = false; store.save(p);
        var restored = new ConstructionStore(directory).load(owner);
        assertEquals(ConstructionProject.Mode.COBBLE, restored.mode); assertFalse(restored.currentLayer);
    }
}
