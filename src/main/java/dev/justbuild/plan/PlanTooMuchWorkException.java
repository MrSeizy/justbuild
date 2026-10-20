package dev.justbuild.plan;

/**
 * A plan's shapes would take too long to draw: they cover far more space than the blocks they place (huge replace
 * boxes, repeats in place, enormous radii). Unlike {@link PlanTooLargeException}, asking for a smaller plan by block
 * count would not help, so the plan is refused.
 */
public final class PlanTooMuchWorkException extends PlanException {
    public PlanTooMuchWorkException() {
        super("its shapes cover far too much space to draw");
    }
}
