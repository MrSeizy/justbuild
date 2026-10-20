package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.justbuild.plan.Cell;
import dev.justbuild.plan.Operation;
import dev.justbuild.plan.Rasterizer;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockResolverTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    @Test
    void parsesIdsAndStates() {
        assertTrue(BlockResolver.parse("stone").orElseThrow().is(Blocks.STONE));
        assertTrue(BlockResolver.parse(" Minecraft:STONE ").orElseThrow().is(Blocks.STONE));
        var stairs = BlockResolver.parse("minecraft:oak_stairs[facing=east,half=top]").orElseThrow();
        assertEquals(Direction.EAST, stairs.getValue(StairBlock.FACING));
        assertEquals(Half.TOP, stairs.getValue(StairBlock.HALF));
    }

    @Test
    void everyStairsBlockMapsToItsFullBlock() {
        // A wrong mapping would leave holes inside thick roofs, so check every stairs block the game has.
        List<String> missing = new java.util.ArrayList<>();
        for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
            if (id.endsWith("_stairs") && BlockResolver.parse(dev.justbuild.plan.BlockSpecs.fullFor(id)).isEmpty()) {
                missing.add(id + " -> " + dev.justbuild.plan.BlockSpecs.fullFor(id));
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void singularIdsOfPluralBlocksResolve() {
        // A live Codex chimney of "minecraft:brick" was skipped and left its cap floating (2026-10-13).
        assertTrue(BlockResolver.parse("minecraft:brick").orElseThrow().is(Blocks.BRICKS));
        assertTrue(BlockResolver.parse("stone_brick").orElseThrow().is(Blocks.STONE_BRICKS));
        assertTrue(BlockResolver.parse("deepslate_tile").orElseThrow().is(Blocks.DEEPSLATE_TILES));
        assertTrue(BlockResolver.parse("oak_plank").orElseThrow().is(Blocks.OAK_PLANKS));
        assertTrue(BlockResolver.parse("brick_stairs[facing=east]").orElseThrow().is(Blocks.BRICK_STAIRS),
                "real ids are untouched");
        assertTrue(BlockResolver.parse("nope_brick").isEmpty());
    }

    @Test
    void placedLeavesDoNotDecay() {
        var specs = BlockResolver.resolveSpecs(List.of("oak_leaves", "minecraft:azalea_leaves",
                "cherry_leaves[persistent=false]", "stone"));
        assertTrue(specs.states().get("oak_leaves").getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT));
        assertTrue(specs.states().get("minecraft:azalea_leaves").getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT));
        assertFalse(specs.states().get("cherry_leaves[persistent=false]").getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT), "unless the plan says so");
        assertTrue(specs.states().get("stone").is(Blocks.STONE));
    }

    @Test
    void everyBlockGivenAnAxisAcceptsIt() {
        // A beam would leave holes if the axis guard added axis= to a block without one.
        List<String> bad = new java.util.ArrayList<>();
        for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
            String spec = dev.justbuild.plan.BlockSpecs.withAxis(id, "x");
            if (!spec.equals(id) && BlockResolver.parse(spec).isEmpty()) {
                bad.add(spec);
            }
        }
        assertEquals(List.of(), bad);
        assertTrue(BlockResolver.parse(dev.justbuild.plan.BlockSpecs.withAxis("stripped_spruce_log", "z")).orElseThrow()
                .getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) == Direction.Axis.Z);
    }

    @Test
    void acceptsOldNamesOfRenamedBlocks() {
        var chain = BlockResolver.parse("minecraft:chain[axis=y]").orElseThrow();
        assertTrue(chain.is(Blocks.IRON_CHAIN));
        assertEquals(Direction.Axis.Y, chain.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS));
        assertTrue(BlockResolver.parse("Chain").orElseThrow().is(Blocks.IRON_CHAIN));
        assertTrue(BlockResolver.parse("minecraft:grass").orElseThrow().is(Blocks.SHORT_GRASS));
        assertTrue(BlockResolver.parse("grass_path").orElseThrow().is(Blocks.DIRT_PATH));
        assertTrue(BlockResolver.parse("chain_command_block").orElseThrow().is(Blocks.CHAIN_COMMAND_BLOCK));
        assertTrue(BlockResolver.parse("grass_block").orElseThrow().is(Blocks.GRASS_BLOCK));
        assertTrue(BlockResolver.parse("minecraft:chains").isEmpty());
    }

    @Test
    void rejectsUnknownBlocksBadPropertiesAndTags() {
        assertTrue(BlockResolver.parse("minecraft:oak_plonk").isEmpty());
        assertTrue(BlockResolver.parse("minecraft:oak_stairs[facing=up]").isEmpty());
        assertTrue(BlockResolver.parse("#minecraft:logs").isEmpty());
        assertTrue(BlockResolver.parse("minecraft:chest{Items:[]}").isEmpty());
    }

    @Test
    void resolveDropsOperationsWithInvalidBlocksOrTrim() {
        Operation good = Operation.set("stone", new Cell(0, 0, 0));
        Operation bad = Operation.set("minecraft:oak_plonk", new Cell(1, 0, 0));
        Operation badTrim = Operation.roof("oak_stairs", "oak_plonk", new Cell(0, 0, 0), new Cell(2, 0, 2), "gable", null);
        BlockResolver.Result r = BlockResolver.resolve(List.of(good, bad, badTrim,
                Operation.set("minecraft:oak_plonk", new Cell(2, 0, 0))));
        assertEquals(List.of(good), r.operations());
        assertEquals(Set.of("minecraft:oak_plonk", "oak_plonk"), r.invalid());
    }

    @Test
    void refusesFireLavaAndTnt() {
        Operation lava = Operation.set("minecraft:lava", new Cell(0, 0, 0));
        Operation stone = Operation.set("stone", new Cell(1, 0, 0));
        BlockResolver.Result r = BlockResolver.resolve(List.of(lava, stone, Operation.set("fire", new Cell(2, 0, 0))));
        assertEquals(List.of(stone), r.operations());
        assertEquals(Set.of("minecraft:lava (not allowed)", "fire (not allowed)"), r.invalid());
        BlockResolver.Specs specs = BlockResolver.resolveSpecs(List.of("tnt", "soul_fire", "stone"));
        assertEquals(Set.of("tnt (not allowed)", "soul_fire (not allowed)"), specs.invalid());
        assertTrue(BlockResolver.parse("minecraft:lava").isPresent(), "the world may still hold lava (baselines)");
    }

    @Test
    void resolveSpecsHandlesDerivedSpecs() {
        BlockResolver.Specs specs = BlockResolver.resolveSpecs(List.of("oak_stairs[facing=south,half=bottom]",
                "oak_slab[type=bottom]", "minecraft:not_a_slab[type=bottom]"));
        assertEquals(Direction.SOUTH, specs.states().get("oak_stairs[facing=south,half=bottom]").getValue(StairBlock.FACING));
        assertTrue(specs.states().get("oak_slab[type=bottom]").is(Blocks.OAK_SLAB));
        assertTrue(specs.states().get(Rasterizer.AIR).isAir());
        assertEquals(Set.of("minecraft:not_a_slab[type=bottom]"), specs.invalid());
    }
}
