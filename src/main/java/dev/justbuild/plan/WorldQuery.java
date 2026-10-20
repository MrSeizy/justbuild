package dev.justbuild.plan;

/** Read access to the world in plan coordinates, for shapes that depend on what is already there. */
public interface WorldQuery {
    /** An empty world: air everywhere, nothing solid. */
    WorldQuery NONE = new WorldQuery() {
        @Override
        public String blockId(Cell cell) {
            return Rasterizer.AIR;
        }

        @Override
        public boolean isSolid(Cell cell) {
            return false;
        }
    };

    /** Namespaced block id at the cell, e.g. "minecraft:grass_block". */
    String blockId(Cell cell);

    /** True for ground a foundation stops at: not air and not replaceable (plants, snow, fluids are not solid). */
    boolean isSolid(Cell cell);
}
