package dev.justbuild.plan;

import java.util.Locale;

/** Quarter turns about the vertical axis. Names match Minecraft's Rotation so they map one to one. */
public enum PlanRotation {
    NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90;

    /** The turn that takes the plan's north-facing frame to a player facing this direction. */
    public static PlanRotation forFacing(String direction) {
        return switch (direction.toLowerCase(Locale.ROOT)) {
            case "north" -> NONE;
            case "east" -> CLOCKWISE_90;
            case "south" -> CLOCKWISE_180;
            case "west" -> COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException("not a horizontal direction: " + direction);
        };
    }

    /** The turn that undoes this one (world offsets back into the plan frame). */
    public PlanRotation inverse() {
        return switch (this) {
            case NONE -> NONE;
            case CLOCKWISE_90 -> COUNTERCLOCKWISE_90;
            case CLOCKWISE_180 -> CLOCKWISE_180;
            case COUNTERCLOCKWISE_90 -> CLOCKWISE_90;
        };
    }

    public Cell apply(Cell c) {
        return switch (this) {
            case NONE -> c;
            case CLOCKWISE_90 -> new Cell(-c.z(), c.y(), c.x());
            case CLOCKWISE_180 -> new Cell(-c.x(), c.y(), -c.z());
            case COUNTERCLOCKWISE_90 -> new Cell(c.z(), c.y(), -c.x());
        };
    }
}
