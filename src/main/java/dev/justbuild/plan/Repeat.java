package dev.justbuild.plan;

/** Apply an operation {@code count} times, shifted by {@code step} each time. */
public record Repeat(int count, Cell step) {
}
