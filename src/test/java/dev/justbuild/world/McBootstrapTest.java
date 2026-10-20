package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class McBootstrapTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    @Test
    void parsesVanillaBlock() throws Exception {
        var result = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:stone", false);
        assertTrue(result.blockState().is(Blocks.STONE));
    }
}
