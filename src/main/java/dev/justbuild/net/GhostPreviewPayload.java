package dev.justbuild.net;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** One bounded, palette-compressed preview. Contains block states only; never block entity data. */
public record GhostPreviewPayload(String dimension, List<String> palette, List<Cell> cells, int total)
        implements CustomPacketPayload {
    /** What this packet carries; older clients decode at most this many, so larger previews use {@link LargeGhostPreviewPayload}. */
    public static final int MAX_CELLS = 100_000;
    /** The most a preview can hold at all, in either packet. */
    public static final int LARGE_MAX_CELLS = dev.justbuild.config.JustBuildConfig.MAX_BLOCKS_LIMIT;
    public static final int MAX_PALETTE = 4096;
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final byte ADD = 0, REPLACE = 1, REMOVE = 2;
    public record Cell(BlockPos pos, int state, byte kind) { }
    public static final Type<GhostPreviewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "ghost_preview"));

    public GhostPreviewPayload {
        int paletteSize = palette.size();
        if (dimension.length() > 256 || palette.size() > MAX_PALETTE || cells.size() > LARGE_MAX_CELLS || total < cells.size()
                || palette.stream().anyMatch(s -> s.length() > 256)
                || cells.stream().anyMatch(c -> c.state() < 0 || c.state() >= paletteSize || c.kind() < ADD || c.kind() > REMOVE)) {
            throw new IllegalArgumentException("Invalid ghost preview.");
        }
        palette = List.copyOf(palette);
        cells = List.copyOf(cells);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, GhostPreviewPayload> CODEC = codec(MAX_CELLS);

    /** The wire format, refusing more than {@code maxCells} cells before allocating them. */
    static StreamCodec<RegistryFriendlyByteBuf, GhostPreviewPayload> codec(int maxCells) {
        return new StreamCodec<>() {
            @Override
            public GhostPreviewPayload decode(RegistryFriendlyByteBuf buf) {
                String dimension = buf.readUtf(256);
                int total = buf.readVarInt();
                int size = bounded(buf.readVarInt(), MAX_PALETTE);
                List<String> palette = new ArrayList<>(size);
                for (int i = 0; i < size; i++) palette.add(buf.readUtf(256));
                int count = bounded(buf.readVarInt(), maxCells);
                List<Cell> cells = new ArrayList<>(count);
                for (int i = 0; i < count; i++) cells.add(new Cell(buf.readBlockPos(), buf.readVarInt(), buf.readByte()));
                return new GhostPreviewPayload(dimension, palette, cells, total);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, GhostPreviewPayload p) {
                buf.writeUtf(p.dimension(), 256);
                buf.writeVarInt(p.total());
                buf.writeVarInt(p.palette().size());
                p.palette().forEach(s -> buf.writeUtf(s, 256));
                buf.writeVarInt(p.cells().size());
                p.cells().forEach(c -> { buf.writeBlockPos(c.pos()); buf.writeVarInt(c.state()); buf.writeByte(c.kind()); });
            }

            private int bounded(int count, int max) {
                if (count < 0 || count > max) throw new IllegalArgumentException("Ghost preview exceeds its limit.");
                return count;
            }
        };
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
