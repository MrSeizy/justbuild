package dev.justbuild.prompt;

/**
 * World facts and guidance sent with each request. {@code placement} says where the origin is (null for edits);
 * {@code style} and {@code direction} are prompt lines or null.
 */
public record BuildContext(String dimension, String biome, String placement, String style, String direction) {
    public BuildContext(String dimension, String biome, String placement) {
        this(dimension, biome, placement, null, null);
    }

    public BuildContext(String dimension, String biome) {
        this(dimension, biome, null, null, null);
    }
}
