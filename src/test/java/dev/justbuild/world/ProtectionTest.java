package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProtectionTest {
    @BeforeAll
    static void bootstrap() {
        McBootstrap.init();
    }

    static final BlockPos FULL_CHEST = new BlockPos(0, 0, 0);
    static final BlockPos OUR_EMPTY_CHEST = new BlockPos(1, 0, 0);
    static final BlockPos THEIR_EMPTY_CHEST = new BlockPos(2, 0, 0);
    static final BlockPos LOOT_CHEST = new BlockPos(3, 0, 0);
    static final BlockPos PLAIN = new BlockPos(4, 0, 0);

    @ParameterizedTest
    @EnumSource(SignTextSlot.class)
    void protectsWrittenSignsAndAllowsShapeChangesOfOurOwnBlocks(SignTextSlot writtenSide) {
        BlockPos signPos = new BlockPos(0, 0, 0);
        BlockPos chestPos = new BlockPos(1, 0, 0);
        BlockState sign = Blocks.OAK_SIGN.defaultBlockState();
        // setText() notifies the level, which a unit test doesn't have; a sign that reports written text stands in.
        var text = net.minecraft.world.level.block.entity.SignText.EMPTY.asMutable()
                .setLine(0, net.minecraft.network.chat.Component.literal("Home")).asImmutable();
        var written = new net.minecraft.world.level.block.entity.SignBlockEntity(signPos, sign) {
            @Override
            public net.minecraft.world.level.block.entity.SignText getText(SignTextSlot slot) {
                return slot == writtenSide ? text : super.getText(slot);
            }
        };
        BlockState single = Blocks.CHEST.defaultBlockState();
        BlockState left = single.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        Map<BlockPos, BlockEntity> entities = Map.of(signPos, written, chestPos, new ChestBlockEntity(chestPos, left));
        Map<BlockPos, BlockState> placements = Map.of(signPos, Blocks.AIR.defaultBlockState(),
                chestPos, Blocks.AIR.defaultBlockState());
        // The build placed both; the chest has since joined a neighbor (shape change) and the sign was written on.
        Map<BlockPos, BlockState> ours = Map.of(signPos, sign, chestPos, single);
        Protection.Result result = Protection.filter(placements, ours, entities::get,
                pos -> pos.equals(chestPos) ? left : sign);
        assertEquals(java.util.Set.of(chestPos), result.placements().keySet());
        assertEquals(1, result.kept());
    }

    @Test
    void keepsPlayerDataButLetsTheBuildChangeItsOwnEmptyBlocks() {
        BlockState chest = Blocks.CHEST.defaultBlockState();
        Map<BlockPos, BlockEntity> entities = new HashMap<>();
        // ItemStacks need bound data components (a loaded world); a chest that reports items stands in for one.
        ChestBlockEntity full = new ChestBlockEntity(FULL_CHEST, chest) {
            @Override
            public boolean isEmpty() {
                return false;
            }
        };
        entities.put(FULL_CHEST, full);
        entities.put(OUR_EMPTY_CHEST, new ChestBlockEntity(OUR_EMPTY_CHEST, chest));
        entities.put(THEIR_EMPTY_CHEST, new ChestBlockEntity(THEIR_EMPTY_CHEST, chest));
        ChestBlockEntity loot = new ChestBlockEntity(LOOT_CHEST, chest);
        loot.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse("minecraft:chests/simple_dungeon")));
        entities.put(LOOT_CHEST, loot);

        Map<BlockPos, BlockState> placements = new LinkedHashMap<>();
        for (BlockPos pos : new BlockPos[] {FULL_CHEST, OUR_EMPTY_CHEST, THEIR_EMPTY_CHEST, LOOT_CHEST, PLAIN}) {
            placements.put(pos, Blocks.STONE.defaultBlockState());
        }
        Map<BlockPos, BlockState> ours = Map.of(FULL_CHEST, chest, OUR_EMPTY_CHEST, chest);

        Protection.Result result = Protection.filter(placements, ours, entities::get, pos -> chest);
        assertEquals(java.util.Set.of(OUR_EMPTY_CHEST, PLAIN), result.placements().keySet());
        assertEquals(3, result.kept());
    }
}
