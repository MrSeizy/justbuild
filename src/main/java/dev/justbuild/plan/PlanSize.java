package dev.justbuild.plan;

import java.util.Locale;

/**
 * How many blocks a plan places, counted the way the limit counts them (every cell, including the air that clears
 * hollow shapes) on flat ground at y = -1, because the worker thread cannot read the world.
 */
public final class PlanSize {
    /** Returned when the plan is larger than the cap given to {@link #blocks}. */
    public static final int OVER = Integer.MAX_VALUE;

    /** Stone below y = 0, air above: what foundations and the review views assume. */
    public static final WorldQuery FLAT_GROUND = new WorldQuery() {
        @Override
        public String blockId(Cell cell) {
            return cell.y() <= -1 ? "minecraft:stone" : Rasterizer.AIR;
        }

        @Override
        public boolean isSolid(Cell cell) {
            return cell.y() <= -2;
        }
    };

    private PlanSize() {
    }

    /** The block count, or {@link #OVER} if it is more than {@code cap}. */
    public static int blocks(BuildPlan plan, int cap) throws PlanException {
        try {
            return Rasterizer.rasterize(plan.operations(), cap, FLAT_GROUND, plan.texture()).size();
        } catch (PlanTooLargeException e) {
            return OVER;
        }
    }

    /** "about 61,200" or "more than 200,000". */
    public static String describe(int blocks, int cap) {
        return blocks == OVER ? "more than " + String.format(Locale.ROOT, "%,d", cap)
                : "about " + String.format(Locale.ROOT, "%,d", blocks);
    }
}
