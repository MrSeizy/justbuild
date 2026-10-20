package dev.justbuild.plan;

/** A block position in the plan's relative frame (+x east, +y up, +z south; the player faces north). */
public record Cell(int x, int y, int z) {
}
