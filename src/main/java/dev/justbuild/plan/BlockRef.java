package dev.justbuild.plan;

/**
 * What a cell should hold: a block spec plus whether a mirror reflected it across the x plane (east/west swap) or the
 * z plane (north/south swap). The world layer applies the flips to the block state before rotating it.
 */
public record BlockRef(String spec, boolean flipX, boolean flipZ) {
    public static final BlockRef AIR = new BlockRef(Rasterizer.AIR, false, false);

    public static BlockRef of(String spec) {
        return new BlockRef(spec, false, false);
    }

    BlockRef flipped(boolean x, boolean z) {
        return x || z ? new BlockRef(spec, flipX ^ x, flipZ ^ z) : this;
    }
}
