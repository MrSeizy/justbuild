package dev.justbuild.world;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchematicCodecTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { McBootstrap.init(); }
    private static int version() { return SharedConstants.getCurrentVersion().dataVersion().version(); }
    private static CompoundTag pos(int x, int y, int z) {
        var t = new CompoundTag(); t.putInt("x",x); t.putInt("y",y); t.putInt("z",z); return t;
    }
    private static CompoundTag paletteEntry(String name) {
        var tag = new CompoundTag(); tag.putString("Name", name); return tag;
    }
    private static Map<BlockPos, BlockState> blocks(SchematicCodec.Decoded decoded) throws Exception {
        return PlanPipeline.run(decoded.plan(), BlockPos.ZERO, Direction.NORTH, LevelHeightAccessor.create(-4096,8192),
                p -> Blocks.AIR.defaultBlockState(), SchematicCodec.MAX_VOLUME).placements();
    }
    @Test void independentlyPackedLitematicaFixtureSupportsCrossLongEntriesAndNegativeRegionSizes() throws Exception {
        var states = List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.GOLD_BLOCK.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST), Blocks.GLASS.defaultBlockState());
        var palette = new ListTag();
        for (String name : List.of("air", "stone", "gold_block", "oak_stairs", "glass")) {
            palette.add(paletteEntry("minecraft:" + name));
        }
        var properties = new CompoundTag(); properties.putString("facing", "east");
        palette.getCompoundOrEmpty(3).put("Properties", properties);
        var region = new CompoundTag(); region.put("Position", pos(3,2,3)); region.put("Size", pos(-26,1,1));
        region.put("BlockStatePalette", palette);
        // 26 successive three-bit values 0,1,2,3,4,..., packed continuously, least significant bit first.
        region.putLongArray("BlockStates", new long[] {0x88d111a223444688L, 0x468L});
        var regions = new CompoundTag(); regions.put("Negative", region);
        var root = new CompoundTag(); root.putInt("Version",7); root.putInt("MinecraftDataVersion",version()); root.put("Regions", regions);
        var decoded = blocks(SchematicCodec.decode(root,"litematic","Fixture",true));
        assertEquals(26, decoded.size());
        for (int i=0;i<26;i++) assertEquals(states.get(i%5), decoded.get(new BlockPos(-22+i,2,3)));
        region.putLongArray("BlockStates", new long[] {0});
        assertThrows(IOException.class, () -> SchematicCodec.decode(root,"litematic","Fixture",true));
    }
    private static CompoundTag sponge(int version) {
        var t = new CompoundTag(); t.putInt("Version", version); t.putInt("DataVersion", version());
        t.putShort("Width",(short)2); t.putShort("Height",(short)2); t.putShort("Length",(short)2); t.putIntArray("Offset",new int[]{-3,-1,4});
        var palette = new CompoundTag(); palette.putInt("minecraft:stone",0); palette.putInt("minecraft:oak_stairs[facing=west]",200);
        var b = version==3 ? new CompoundTag() : t; b.put("Palette",palette);
        b.putByteArray(version==3?"Data":"BlockData",new byte[]{0,(byte)0xc8,1,0,0,0,0,0,(byte)0xc8,1});
        if(version==3){t.put("Blocks",b); var root=new CompoundTag(); root.put("Schematic",t); return root;}
        return t;
    }
    @Test void spongeTwoAndThreeUseVarintsAndXThenZThenYIndexing() throws Exception {
        for(int v:List.of(2,3)) {
            var cells=blocks(SchematicCodec.decode(sponge(v),"schem","Fixture",true)); assertEquals(8,cells.size());
            assertEquals(Direction.WEST,cells.get(new BlockPos(-2,-1,4)).getValue(StairBlock.FACING));
            assertEquals(Direction.WEST,cells.get(new BlockPos(-2,0,5)).getValue(StairBlock.FACING));
            assertTrue(cells.get(new BlockPos(-3,0,4)).is(Blocks.STONE));
        }
    }
    @Test void bothFormatsRoundTripStatesOffsetsExplicitAirAndUnspecifiedGaps() throws Exception {
        Map<BlockPos,BlockState> input=new LinkedHashMap<>(); input.put(new BlockPos(-2,-1,3),Blocks.STONE.defaultBlockState());
        input.put(new BlockPos(0,0,4),Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH));
        input.put(new BlockPos(-1,0,4),Blocks.AIR.defaultBlockState());
        for(String format:List.of("litematic","schem")) {
            var encoded=SchematicCodec.encode(input,"House",format); var out=new ByteArrayOutputStream(); NbtIo.writeCompressed(encoded,out);
            assertEquals(input,blocks(SchematicCodec.read(out.toByteArray(),format,"Fallback",true)));
            var noAir=blocks(SchematicCodec.read(out.toByteArray(),format,"Fallback",false));
            assertEquals(2,noAir.size()); assertFalse(noAir.containsKey(new BlockPos(-1,0,4)));
            assertFalse(noAir.containsKey(new BlockPos(-2,0,3)));
        }
    }
    @Test void sparseMultipleRegionsKeepTheirRelativePositionsAndRefuseConflictingOverlap() throws Exception {
        var root=SchematicCodec.encode(Map.of(new BlockPos(0,0,0),Blocks.STONE.defaultBlockState()),"Test","litematic");
        var regions=root.getCompoundOrEmpty("Regions"); var second=regions.getCompoundOrEmpty("Build").copy();
        second.put("Position",pos(-5,2,3)); regions.put("Second",second);
        assertEquals(2,blocks(SchematicCodec.decode(root,"litematic","Test",true)).size());
        second.put("Position",pos(0,0,0)); var palette=second.getListOrEmpty("BlockStatePalette");
        palette.set(2,paletteEntry("minecraft:gold_block"));
        assertThrows(IOException.class, () -> SchematicCodec.decode(root,"litematic","Test",true));
    }
    @Test void malformedAndUnsupportedDataIsRejectedBeforeBecomingADesign() throws Exception {
        var root=sponge(3); var schematic=root.getCompoundOrEmpty("Schematic");
        schematic.putShort("Width",(short)0); assertThrows(IOException.class,()->SchematicCodec.decode(root,"schem","Bad",true));
        schematic.putShort("Width",(short)2); schematic.putInt("DataVersion",Integer.MAX_VALUE);
        assertThrows(IOException.class,()->SchematicCodec.decode(root,"schem","Bad",true));
        schematic.putInt("DataVersion",version()); var palette=schematic.getCompoundOrEmpty("Blocks").getCompoundOrEmpty("Palette");
        palette.putInt("missing_mod:unknown",201); assertThrows(IOException.class,()->SchematicCodec.decode(root,"schem","Bad",true));
        assertThrows(IOException.class,()->SchematicCodec.read(new byte[]{1,2,3},"schem","Bad",true));
    }
    @Test void sharedLibraryExportsAndImportsBothFormatsWithoutAcceptingPaths() throws Exception {
        var transfer=new DesignTransfer(directory);
        var plan=PlanSnapshot.capture("House",BlockPos.ZERO,Direction.NORTH,Map.of(BlockPos.ZERO,Blocks.STONE.defaultBlockState()));
        var design=new DesignStore.Design("12345678901234567890123456789012","owner","House",plan,0);
        for(String format:List.of("litematic","schem")) {
            String file=transfer.exportDesign(design,format); assertTrue(Files.exists(directory.resolve(file)));
            assertEquals("House",transfer.read(file).name()); assertTrue(transfer.files().contains(file));
        }
        assertThrows(IOException.class,()->transfer.read("../escape.litematic"));
        assertThrows(IOException.class,()->transfer.read("NUL.schem"));
    }
}
