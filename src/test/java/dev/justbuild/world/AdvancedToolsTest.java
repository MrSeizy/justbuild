package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.justbuild.plan.BuildPlan;
import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import dev.justbuild.prompt.PlanningAreas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdvancedToolsTest {
    @TempDir Path dir;
    @BeforeAll static void bootstrap() { McBootstrap.init(); }

    @Test void regionsKeepHeightAndRotateIntoThePlanFrame() {
        BuildRegion region = BuildRegion.between(Level.OVERWORLD, new BlockPos(11, 71, 12), new BlockPos(10, 70, 10));
        assertTrue(region.contains(new BlockPos(10, 70, 10)));
        assertFalse(region.contains(new BlockPos(10, 69, 10)));
        assertFalse(region.contains(new BlockPos(12, 70, 10)));
        assertEquals(new PlanningAreas.Box(0, 0, -1, 2, 1, 0), region.relativeTo(new BlockPos(10, 70, 10), Direction.EAST));
        assertEquals(region, BuildRegion.parse(region.toJson()));
        assertThrows(IllegalArgumentException.class, () -> BuildRegion.between(Level.OVERWORLD, BlockPos.ZERO, new BlockPos(256, 0, 0)));
    }

    @Test void extendSelectionKeepsFootprintWhenAddingRoofAndCanGrowEveryAxis() {
        var footprint = BuildRegion.between(Level.OVERWORLD, new BlockPos(-12, -4, -8), new BlockPos(-5, -4, -2));
        var roof = footprint.including(new BlockPos(-9, 7, -3));
        assertEquals(footprint.min(), roof.min());
        assertEquals(new BlockPos(-5, 7, -2), roof.max());
        var expanded = roof.including(new BlockPos(-15, -6, 2));
        assertEquals(new BlockPos(-15, -6, -8), expanded.min());
        assertEquals(new BlockPos(-5, 7, 2), expanded.max());
        assertEquals(expanded, expanded.including(new BlockPos(-9, 0, -3)), "an interior point cannot shrink the box");
    }

    @Test void extendSelectionEnforcesInclusiveSpanAndHeightLimits() {
        var start = BuildRegion.between(Level.OVERWORLD, BlockPos.ZERO, BlockPos.ZERO);
        var largest = start.including(new BlockPos(255, 255, 255));
        for (BlockPos outside : List.of(new BlockPos(256, 0, 0), new BlockPos(0, 256, 0), new BlockPos(0, 0, 256),
                new BlockPos(-1, 0, 0))) {
            assertThrows(IllegalArgumentException.class, () -> largest.including(outside));
        }
        var high = BuildRegion.between(Level.OVERWORLD, new BlockPos(0, 4096, 0), new BlockPos(0, 4096, 0));
        assertThrows(IllegalArgumentException.class, () -> high.including(new BlockPos(0, 4097, 0)));
    }

    @Test void eachPartCornerCanShrinkTheBoxWhileKeepingItsOppositeFixed() {
        var region = BuildRegion.between(Level.OVERWORLD, BlockPos.ZERO, new BlockPos(8, 8, 8));
        var corners = List.of(new BlockPos(0, 0, 0), new BlockPos(8, 0, 0), new BlockPos(0, 8, 0), new BlockPos(8, 8, 0),
                new BlockPos(0, 0, 8), new BlockPos(8, 0, 8), new BlockPos(0, 8, 8), new BlockPos(8, 8, 8));
        BlockPos target = new BlockPos(3, 4, 5);
        for (int i = 0; i < 8; i++) {
            assertEquals(corners.get(i), region.corner(i));
            var resized = region.corner(i, target);
            assertEquals(target, resized.corner(i));
            assertEquals(corners.get(i ^ 7), resized.corner(i ^ 7), "the opposite corner must stay fixed");
            assertEquals(Level.OVERWORLD, resized.dimension());
        }
    }

    @Test void partCornersCanExpandFlatSelectionsWithoutMovingTheOppositeSide() {
        var flat = BuildRegion.between(Level.OVERWORLD, BlockPos.ZERO, new BlockPos(4, 0, 4));
        var raised = flat.corner(6, new BlockPos(-2, 5, 6));
        assertEquals(new BlockPos(-2, 0, 0), raised.min());
        assertEquals(new BlockPos(4, 5, 6), raised.max());
        var lowered = flat.corner(1, new BlockPos(6, -3, -2));
        assertEquals(new BlockPos(0, -3, -2), lowered.min());
        assertEquals(new BlockPos(6, 0, 4), lowered.max());
    }

    @Test void partCornerMovesRejectCrossingBoundsInvalidIndicesAndExcessiveSpans() {
        var region = BuildRegion.between(Level.OVERWORLD, BlockPos.ZERO, new BlockPos(8, 8, 8));
        assertThrows(IllegalArgumentException.class, () -> region.corner(-1));
        assertThrows(IllegalArgumentException.class, () -> region.corner(8, BlockPos.ZERO));
        assertThrows(IllegalArgumentException.class, () -> region.corner(6, new BlockPos(9, 8, 8)));
        assertThrows(IllegalArgumentException.class, () -> region.corner(6, new BlockPos(0, -1, 8)));
        assertThrows(IllegalArgumentException.class, () -> region.corner(6, new BlockPos(0, 8, -1)));
        assertThrows(IllegalArgumentException.class, () -> region.corner(7, new BlockPos(256, 8, 8)));
    }

    @Test void locksSurviveReloadAndCorruptionDoesNotBecomeAnEmptyStore() throws Exception {
        RegionStore store = new RegionStore(dir.resolve("locks.json"));
        var lock = new RegionStore.Locked("abcdef12", "owner", "Bedroom", BuildRegion.between(Level.OVERWORLD, BlockPos.ZERO, new BlockPos(3, 4, 5)));
        store.save(List.of(lock));
        assertEquals(List.of(lock), store.load());
        Files.writeString(dir.resolve("locks.json"), "{broken");
        assertThrows(java.io.IOException.class, store::load);
    }

    @Test void roomSelectionIncludesItsShellAndRefusesAnOpenRoom() {
        BuildRegion bounds = BuildRegion.between(Level.OVERWORLD, BlockPos.ZERO, new BlockPos(4, 4, 4));
        java.util.function.Function<BlockPos, BlockState> closed = p -> bounds.contains(p)
                && (p.getX() == 0 || p.getX() == 4 || p.getY() == 0 || p.getY() == 4 || p.getZ() == 0 || p.getZ() == 4)
                ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        assertEquals(bounds, PartSelections.room(bounds, new BlockPos(2, 2, 2), closed));
        BlockPos hole = new BlockPos(0, 2, 2);
        assertThrows(IllegalArgumentException.class, () -> PartSelections.room(bounds, new BlockPos(2, 2, 2),
                p -> p.equals(hole) ? Blocks.AIR.defaultBlockState() : closed.apply(p)));
        BuildRegion wall = PartSelections.wall(bounds, new BlockPos(0, 2, 2), Direction.WEST);
        assertEquals(0, wall.min().getX()); assertEquals(0, wall.max().getX());
        assertThrows(IllegalArgumentException.class, () -> PartSelections.wall(bounds, BlockPos.ZERO, Direction.UP));
    }

    @Test void qualityChecksFlagBlockedDoorLandingAndUnsupportedSections() {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        plan.put(new BlockPos(0, 1, 0), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.NORTH));
        plan.put(new BlockPos(0, 1, -1), Blocks.STONE.defaultBlockState());
        plan.put(new BlockPos(4, 1, 0), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
        var warnings = BuildQuality.inspect(plan, p -> Blocks.AIR.defaultBlockState());
        assertTrue(warnings.stream().anyMatch(s -> s.contains("doorway")));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("landing")));
        assertTrue(warnings.stream().anyMatch(s -> s.contains("solid contact")));
        assertTrue(BuildQuality.inspect(Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()),
                p -> p.getY() < 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState()).isEmpty());
    }

    @Test void smallThumbnailsFillTheirPixelsAndUseTheTopBlockColor() {
        BuildPlan one = new BuildPlan("One", "", List.of(Operation.set("diamond_block", new Cell(0, 0, 0))));
        DesignThumbnail.Image image = DesignThumbnail.create(one);
        assertFalse(image.colors().isEmpty());
        assertFalse(image.pixels().contains("."), "one block should fill the thumbnail, not become a single dot");
        assertEquals(image, DesignThumbnail.Image.parse(image.toJson()));
        BuildPlan covered = new BuildPlan("Covered", "", List.of(Operation.set("stone", new Cell(0, 0, 0)),
                Operation.set("diamond_block", new Cell(0, 1, 0))));
        assertEquals(image, DesignThumbnail.create(covered));
        assertThrows(IllegalArgumentException.class, () -> new DesignThumbnail.Image(List.of(0xffffff), "f".repeat(256)));
    }

    @Test void portableDesignsRoundTripWithoutOwnerAndRejectEscapingPaths() throws Exception {
        BuildPlan plan = new BuildPlan("Cottage", "", List.of(Operation.set("stone", new Cell(0, 0, 0))));
        var design = new DesignStore.Design("abcdef12", "private-owner", "Cottage", plan, 1, "Houses", DesignThumbnail.create(plan));
        DesignTransfer transfer = new DesignTransfer(dir.resolve("library"));
        String file = transfer.exportDesign(design);
        assertEquals(List.of(file), transfer.files());
        var imported = transfer.read(file);
        assertEquals(plan, imported.plan()); assertEquals("Houses", imported.folder());
        var json = JsonParser.parseString(Files.readString(transfer.directory().resolve(file))).getAsJsonObject();
        assertFalse(json.has("owner")); assertFalse(json.has("id"));
        assertThrows(java.io.IOException.class, () -> transfer.read("../private.justbuild.json"));
        Path privateFile = dir.resolve("private.justbuild.json"); Files.writeString(privateFile, "private");
        Files.createSymbolicLink(transfer.directory().resolve("linked.justbuild.json"), privateFile);
        assertThrows(java.io.IOException.class, () -> transfer.read("linked.justbuild.json"));
        assertFalse(transfer.files().contains("linked.justbuild.json"));
        DesignStore store = new DesignStore(dir.resolve("world-designs")); store.save(design);
        assertEquals(List.of(design), store.loadAll());
    }
}
