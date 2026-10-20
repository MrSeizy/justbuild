package dev.justbuild.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockSpecsTest {
    @Test
    void ids() {
        assertEquals("minecraft:oak_stairs", BlockSpecs.id(" Minecraft:OAK_STAIRS[facing=north] "));
        assertEquals("minecraft:stone", BlockSpecs.id("stone"));
        assertEquals("mymod:thing", BlockSpecs.id("mymod:thing"));
        assertTrue(BlockSpecs.isStairs("cut_copper_stairs[half=top]"));
        assertFalse(BlockSpecs.isStairs("oak_planks"));
        assertTrue(BlockSpecs.sameBlock("grass_block", "minecraft:grass_block[snowy=false]"));
        assertFalse(BlockSpecs.sameBlock("grass_block", "dirt"));
    }

    @Test
    void withPropertiesMergesAndOverrides() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("facing", "north");
        props.put("half", "bottom");
        assertEquals("minecraft:oak_stairs[waterlogged=true,facing=north,half=bottom]",
                BlockSpecs.withProperties("minecraft:oak_stairs[waterlogged=true,facing=east]", props));
        assertEquals("oak_stairs[facing=north,half=bottom]", BlockSpecs.withProperties("oak_stairs", props));
    }

    @Test
    void slabs() {
        assertEquals("minecraft:oak_slab[type=bottom]", BlockSpecs.slabFor("minecraft:oak_stairs[facing=north]"));
        assertEquals("stone_brick_slab[type=bottom]", BlockSpecs.slabFor("stone_brick_stairs"));
    }
}
