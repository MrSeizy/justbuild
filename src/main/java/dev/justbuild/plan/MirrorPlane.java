package dev.justbuild.plan;

/** Also apply an operation reflected across the plane x = at (axis "x") or z = at (axis "z"); at is a multiple of 0.5. */
public record MirrorPlane(String axis, double at) {
    /** 2 × at as an integer, so a reflected coordinate is {@code twiceAt() - c}. */
    public int twiceAt() {
        return (int) Math.round(at * 2);
    }
}
