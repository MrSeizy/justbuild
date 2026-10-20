package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StructureCompleterTest {
    static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 384);

    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    static BlockState parse(String spec) {
        return BlockResolver.parse(spec).orElseThrow();
    }

    @Test
    void addsDoorUpperHalf() {
        Map<BlockPos, BlockState> map = new LinkedHashMap<>();
        BlockPos pos = new BlockPos(0, 64, 0);
        map.put(pos, parse("oak_door[facing=east,half=lower,hinge=right]"));
        assertEquals(1, StructureCompleter.complete(map, HEIGHT));
        BlockState upper = map.get(pos.above());
        assertTrue(upper.is(Blocks.OAK_DOOR));
        assertEquals(DoubleBlockHalf.UPPER, upper.getValue(DoorBlock.HALF));
        assertEquals(Direction.EAST, upper.getValue(DoorBlock.FACING));
    }

    @Test
    void keepsExplicitUpperHalfAndAddsTallPlantTop() {
        Map<BlockPos, BlockState> map = new LinkedHashMap<>();
        map.put(new BlockPos(0, 64, 0), parse("oak_door[half=lower]"));
        map.put(new BlockPos(0, 65, 0), parse("oak_door[half=upper]"));
        map.put(new BlockPos(5, 64, 0), parse("sunflower[half=lower]"));
        assertEquals(1, StructureCompleter.complete(map, HEIGHT));
        assertTrue(map.get(new BlockPos(5, 65, 0)).is(Blocks.SUNFLOWER));
    }

    @Test
    void addsBedHeadInFacingDirection() {
        Map<BlockPos, BlockState> map = new LinkedHashMap<>();
        map.put(new BlockPos(0, 64, 0), parse("red_bed[facing=east,part=foot]"));
        StructureCompleter.complete(map, HEIGHT);
        BlockState head = map.get(new BlockPos(1, 64, 0));
        assertEquals(BedPart.HEAD, head.getValue(BedBlock.PART));
    }
}
