package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.Cell;
import dev.justbuild.prompt.PlanningFeedback;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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

class RouteFindingsTest {
    @BeforeAll static void bootstrap() { McBootstrap.init(); }
    private static BlockState stone() { return Blocks.STONE.defaultBlockState(); }

    /** Stone ground at y ≤ 0 everywhere, with the draft's blocks on top. */
    private static final class Scene {
        final Map<BlockPos, BlockState> draft = new LinkedHashMap<>();
        void put(int x, int y, int z, BlockState state) { draft.put(new BlockPos(x, y, z), state); }
        void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (BlockPos p : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) draft.put(p.immutable(), state);
        }
        BlockState at(BlockPos p) {
            BlockState placed = draft.get(p);
            return placed != null ? placed : p.getY() <= 0 ? stone() : Blocks.AIR.defaultBlockState();
        }
        List<PlanningFeedback.Problem> find() { return find(this::at); }
        List<PlanningFeedback.Problem> find(Function<BlockPos, BlockState> read) {
            return RouteFindings.find(Level.OVERWORLD, draft.keySet(), read, BlockPos.ZERO, Direction.NORTH);
        }
    }

    private static void door(Scene s, int x, int z) {
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        s.put(x, 1, z, door); s.put(x, 2, z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** A 7 × 7 stone room with an oak door in its north wall, an upper floor at y = 3 and a ceiling at y = 9. */
    private static Scene tower() {
        Scene s = new Scene();
        for (int y = 1; y <= 9; y++) for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++)
            if (x == 0 || x == 6 || z == 0 || z == 6 || y == 9) s.put(x, y, z, stone());
        s.fill(1, 3, 1, 5, 3, 5, stone());
        door(s, 3, 0);
        return s;
    }

    /** Stairs from the ground floor up to the upper floor (the same flight WorkshopToolsTest uses). */
    private static void stairs(Scene s) {
        for (int x = 2; x <= 4; x++) {
            s.put(x, 3, 2, Blocks.AIR.defaultBlockState());
            s.put(x, x - 1, 2, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        }
    }

    private static PlanningFeedback.Problem start(int x, int y, int z) {
        return new PlanningFeedback.Problem(PlanningFeedback.Kind.ROUTE_START, new Cell(x, y, z));
    }

    private static PlanningFeedback.Problem area(Cell low, Cell high, int count) {
        return new PlanningFeedback.Problem(PlanningFeedback.Kind.UNREACHABLE, low, high, count, List.of());
    }

    @Test void anUpperFloorWithoutStairsIsReportedFromTheDoor() {
        assertEquals(List.of(start(3, 1, 0), area(new Cell(1, 4, 1), new Cell(5, 4, 5), 25)), tower().find());
    }

    @Test void stairsConnectTheFloorAndLeaveNothingToReport() {
        Scene s = tower(); stairs(s);
        assertEquals(List.of(), s.find());
    }

    @Test void findingsAreInThePlanFrame() {
        // Facing east, world offset (wx, wy, wz) from the origin is plan (wz, wy, -wx).
        Scene s = tower();
        assertEquals(List.of(start(0, 1, 7), area(new Cell(1, 4, 5), new Cell(5, 4, 9), 25)),
                RouteFindings.find(Level.OVERWORLD, s.draft.keySet(), s::at, new BlockPos(10, 0, 0), Direction.EAST));
    }

    @Test void theTallestTowersPlatformInsideItsParapetIsSeen() {
        // routes() drops the top layer as roof; the parapet is that layer, so the platform needs the one-block raise.
        Scene s = new Scene();
        s.fill(0, 1, 0, 4, 5, 4, stone());
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) if (x == 0 || x == 4 || z == 0 || z == 4) s.put(x, 6, z, stone());
        assertEquals(List.of(start(-1, 1, -1), area(new Cell(1, 6, 1), new Cell(3, 6, 3), 9)), s.find());
    }

    @Test void aWallWalkOnTheEdgeIsReportedButNaturalGroundBesideItIsNot() {
        Scene s = new Scene();
        for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++)
            if (x == 0 || x == 6 || z == 0 || z == 6) s.fill(x, 1, z, x, 3, z, stone());
        door(s, 3, 0);
        s.fill(3, 1, 3, 3, 6, 3, stone()); // a mast, so the wall top isn't the region's top layer
        // A raised strip of natural ground outside the west wall, level with the wall walk; not part of the draft.
        Function<BlockPos, BlockState> read = p -> p.getX() == -1 && p.getY() >= 1 && p.getY() <= 3
                && p.getZ() >= 0 && p.getZ() <= 6 ? stone() : s.at(p);
        assertEquals(List.of(start(3, 1, 0), area(new Cell(0, 4, 0), new Cell(6, 4, 6), 24)), s.find(read));
    }

    @Test void leavesSmallPocketsAndStairRoofsAreIgnored() {
        Scene canopy = tower(); stairs(canopy); canopy.fill(1, 5, 4, 2, 5, 5, Blocks.OAK_LEAVES.defaultBlockState());
        assertEquals(List.of(), canopy.find(), "a leaf canopy is not a floor");
        Scene ledge = tower(); stairs(ledge); ledge.fill(1, 5, 4, 2, 5, 5, stone());
        assertEquals(List.of(start(3, 1, 0), area(new Cell(1, 6, 4), new Cell(2, 6, 5), 4)), ledge.find(), "the same spaces on stone");
        Scene pocket = tower(); stairs(pocket); pocket.fill(1, 5, 4, 2, 5, 5, stone()); pocket.put(2, 5, 5, Blocks.AIR.defaultBlockState());
        assertEquals(List.of(), pocket.find(), "three spaces are too few");
        Scene roof = tower(); stairs(roof);
        roof.fill(1, 5, 5, 5, 5, 5, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        assertEquals(List.of(), roof.find(), "a run of stairs alone is a roof, not a floor");
    }

    @Test void aCottageOneStepAboveTheGroundIsReachable() {
        // The approach pushes the door off the box edge, so the start is the ground ring, and the floor is one step up.
        Scene s = new Scene();
        s.fill(0, 1, 0, 8, 1, 6, stone());
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 6; z++) if (x == 0 || x == 8 || z == 0 || z == 6) s.fill(x, 2, z, x, 4, z, stone());
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        s.put(4, 2, 0, door); s.put(4, 3, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        s.fill(3, 1, -5, 5, 1, -1, stone());
        assertEquals(List.of(), s.find());
    }

    @Test void aGatelessCourtyardOfNaturalGroundIsReported() {
        // The courtyard counts by the interior rule alone; equal areas are ordered by their lowest space.
        Scene s = new Scene();
        for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++)
            if (x == 0 || x == 6 || z == 0 || z == 6) s.fill(x, 1, z, x, 3, z, stone());
        s.fill(3, 1, 3, 3, 6, 3, stone()); // a mast, so the wall top isn't the region's top layer
        assertEquals(List.of(start(-1, 1, -1), area(new Cell(1, 1, 1), new Cell(5, 1, 5), 24),
                area(new Cell(0, 4, 0), new Cell(6, 4, 6), 24)), s.find());
    }

    @Test void aFloorWithAFewStairsIsStillAFloor() {
        // 23 floor spaces at feet 4 (two lose their headroom to the stairs) and 2 stair spaces at feet 5.
        Scene s = tower();
        s.fill(2, 4, 2, 3, 4, 2, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        assertEquals(List.of(start(3, 1, 0), area(new Cell(1, 4, 1), new Cell(5, 5, 5), 25)), s.find());
    }

    @Test void onlyTheFourLargestAreasAreReported() {
        Scene s = new Scene();
        int x = 0;
        for (int[] size : new int[][] {{2, 2}, {2, 3}, {3, 3}, {3, 4}, {4, 4}}) {
            s.fill(x, 1, 0, x + size[0] - 1, 2, size[1] - 1, stone());
            x += size[0] + 2;
        }
        s.fill(0, 1, 8, 0, 6, 8, stone()); // a mast keeps the platforms below the top layer
        var found = s.find();
        assertEquals(PlanningFeedback.Kind.ROUTE_START, found.getFirst().kind());
        assertEquals(List.of(16, 12, 9, 6), found.stream().skip(1).map(PlanningFeedback.Problem::count).toList());
    }

    @Test void anOversizedEmptyOrUnloadedAreaGivesNothing() {
        Function<BlockPos, BlockState> unread = p -> { fail("an oversized region read the world"); return null; };
        assertEquals(List.of(), RouteFindings.find(Level.OVERWORLD, List.of(BlockPos.ZERO, new BlockPos(200, 1, 200)),
                unread, BlockPos.ZERO, Direction.NORTH));
        assertEquals(List.of(), RouteFindings.find(Level.OVERWORLD, List.of(BlockPos.ZERO, new BlockPos(300, 1, 0)),
                unread, BlockPos.ZERO, Direction.NORTH));
        assertEquals(List.of(), RouteFindings.find(Level.OVERWORLD, List.of(), unread, BlockPos.ZERO, Direction.NORTH));
        Scene s = tower();
        assertEquals(List.of(), s.find(p -> p.getX() == 7 ? null : s.at(p)));
    }
}
