package dev.justbuild.plan;

/** A plan covers more cells than the limit (maxBlocks); air that clears the inside of hollow shapes counts too. */
public final class PlanTooLargeException extends PlanException {
    private final int limit;

    public PlanTooLargeException(int limit) {
        super("it places more than " + limit + " blocks");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
