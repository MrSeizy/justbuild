package dev.justbuild.plan;

import java.util.List;
import java.util.Map;

/**
 * One shape in a build plan. Fields a shape does not use are null (hollow is false). The parser fills defaults
 * (sphere/ellipsoid style "full", roof style "gable", arch axis "x" and height 0), and the factories do the same.
 * A roof's {@code edge} is the rim block (null: the roof block), {@code pitch} 0 (low), 1 (45°), or 2 (steep; null: 1),
 * {@code rim} and {@code eaves} turn off the gable rim and the stairs under the eaves when false (null: on),
 * {@code flare} true starts the roof with a slab course so the eaves curve out, {@code mix} is up to four blocks
 * scattered through a roof's surface or a fill, {@code beam} is a log run along a roof's eaves (and across its gables),
 * and {@code finial} is the blocks stacked on each end of a roof's ridge, bottom first.
 */
public record Operation(OpType op, String block, Cell from, Cell to, Double radius, Integer height, boolean hollow,
        String trim, String style, String axis, String direction, Radii radii, Map<Character, String> palette,
        List<List<String>> layers, String target, Repeat repeat, MirrorPlane mirror, String edge, Integer pitch,
        Boolean rim, Boolean eaves, Boolean flare, List<String> mix, String beam, List<String> finial) {

    public Operation {
        mix = mix == null || mix.isEmpty() ? null : List.copyOf(mix);
        finial = finial == null ? null : List.copyOf(finial);
    }

    public Operation(OpType op, String block, Cell from, Cell to, Double radius, Integer height, boolean hollow) {
        this(op, block, from, to, radius, height, hollow, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /** Every field but the roof-only edge and pitch. */
    public Operation(OpType op, String block, Cell from, Cell to, Double radius, Integer height, boolean hollow,
            String trim, String style, String axis, String direction, Radii radii, Map<Character, String> palette,
            List<List<String>> layers, String target, Repeat repeat, MirrorPlane mirror) {
        this(op, block, from, to, radius, height, hollow, trim, style, axis, direction, radii, palette, layers, target,
                repeat, mirror, null, null, null, null, null, null, null, null);
    }

    public static Operation set(String block, Cell at) {
        return new Operation(OpType.SET, block, at, null, null, null, false);
    }

    public static Operation fill(String block, Cell from, Cell to, boolean hollow) {
        return new Operation(OpType.FILL, block, from, to, null, null, hollow);
    }

    public static Operation line(String block, Cell from, Cell to) {
        return new Operation(OpType.LINE, block, from, to, null, null, false);
    }

    public static Operation sphere(String block, Cell center, double radius, boolean hollow) {
        return sphere(block, center, radius, "full", hollow);
    }

    public static Operation sphere(String block, Cell center, double radius, String style, boolean hollow) {
        return new Operation(OpType.SPHERE, block, center, null, radius, null, hollow, null, style, null, null, null,
                null, null, null, null, null);
    }

    public static Operation ellipsoid(String block, Cell center, Radii radii, String style, boolean hollow) {
        return new Operation(OpType.ELLIPSOID, block, center, null, null, null, hollow, null, style, null, null, radii,
                null, null, null, null, null);
    }

    public static Operation cylinder(String block, Cell base, double radius, int height, boolean hollow) {
        return new Operation(OpType.CYLINDER, block, base, null, radius, height, hollow);
    }

    public static Operation roof(String block, String trim, Cell from, Cell to, String style, String axis) {
        return roof(block, trim, from, to, style, axis, null, null);
    }

    public static Operation roof(String block, String trim, Cell from, Cell to, String style, String axis, String edge,
            Integer pitch) {
        return roof(block, trim, from, to, style, axis, edge, pitch, null, null);
    }

    public static Operation roof(String block, String trim, Cell from, Cell to, String style, String axis, String edge,
            Integer pitch, Boolean rim, Boolean eaves) {
        return roof(block, trim, from, to, style, axis, edge, pitch, rim, eaves, null, null);
    }

    public static Operation roof(String block, String trim, Cell from, Cell to, String style, String axis, String edge,
            Integer pitch, Boolean rim, Boolean eaves, Boolean flare, String mix) {
        return roof(block, trim, from, to, style, axis, edge, pitch, rim, eaves, flare,
                mix == null ? null : List.of(mix), null, null);
    }

    public static Operation roof(String block, String trim, Cell from, Cell to, String style, String axis, String edge,
            Integer pitch, Boolean rim, Boolean eaves, Boolean flare, List<String> mix, String beam,
            List<String> finial) {
        return new Operation(OpType.ROOF, block, from, to, null, null, false, trim, style, axis, null, null, null,
                null, null, null, null, edge, pitch, rim, eaves, flare, mix, beam, finial);
    }

    public static Operation stairs(String block, String trim, Cell from, Cell to, String direction, int height) {
        return new Operation(OpType.STAIRS, block, from, to, null, height, false, trim, null, null, direction, null,
                null, null, null, null, null);
    }

    public static Operation arch(String block, Cell from, double radius, int height, String axis, boolean hollow) {
        return arch(block, from, radius, height, axis, hollow, null);
    }

    /** {@code style} "pointed" makes a gothic arch; null or "round" a half circle. */
    public static Operation arch(String block, Cell from, double radius, int height, String axis, boolean hollow,
            String style) {
        return new Operation(OpType.ARCH, block, from, null, radius, height, hollow, null, style == null ? "round" : style,
                axis, null, null,
                null, null, null, null, null);
    }

    public static Operation voxels(Map<Character, String> palette, Cell from, List<List<String>> layers) {
        return new Operation(OpType.VOXELS, null, from, null, null, null, false, null, null, null, null, null,
                Map.copyOf(palette), layers.stream().map(List::copyOf).toList(), null, null, null);
    }

    public static Operation replace(String block, String target, Cell from, Cell to) {
        return new Operation(OpType.REPLACE, block, from, to, null, null, false, null, null, null, null, null, null,
                null, target, null, null);
    }

    public static Operation foundation(String block, Cell from, Cell to) {
        return new Operation(OpType.FOUNDATION, block, from, to, null, null, false);
    }

    public Operation withRepeat(Repeat value) {
        return new Operation(op, block, from, to, radius, height, hollow, trim, style, axis, direction, radii, palette,
                layers, target, value, mirror, edge, pitch, rim, eaves, flare, mix, beam, finial);
    }

    public Operation withMirror(MirrorPlane value) {
        return new Operation(op, block, from, to, radius, height, hollow, trim, style, axis, direction, radii, palette,
                layers, target, repeat, value, edge, pitch, rim, eaves, flare, mix, beam, finial);
    }

    /** The same operation with other blocks scattered through it (fills and roofs). */
    public Operation withMix(String... values) {
        return new Operation(op, block, from, to, radius, height, hollow, trim, style, axis, direction, radii, palette,
                layers, target, repeat, mirror, edge, pitch, rim, eaves, flare, List.of(values), beam, finial);
    }
}
