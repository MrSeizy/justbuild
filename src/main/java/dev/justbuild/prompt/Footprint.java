package dev.justbuild.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.HashSet;
import java.util.List;

/** A small, simple polygon whose vertices and boundary refer to block centers. */
public final class Footprint {
    public record Point(int x, int z) {
        public Point {
            if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000)
                throw new IllegalArgumentException("Footprint coordinates are outside the world.");
        }
    }

    private Footprint() { }

    public static List<Point> validate(List<Point> input) {
        List<Point> points = List.copyOf(input);
        if (points.isEmpty()) return points;
        if (points.size() < 3 || points.size() > 32 || new HashSet<>(points).size() != points.size())
            throw new IllegalArgumentException("A polygon needs 3–32 distinct corners.");
        long area = 0;
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i), b = points.get((i + 1) % points.size());
            area += (long) a.x * b.z - (long) b.x * a.z;
            for (int j = i + 1; j < points.size(); j++) {
                if (j == i + 1 || i == 0 && j == points.size() - 1) continue;
                if (intersects(a, b, points.get(j), points.get((j + 1) % points.size())))
                    throw new IllegalArgumentException("Polygon edges cannot cross or touch each other.");
            }
        }
        if (area == 0) throw new IllegalArgumentException("The polygon must enclose an area.");
        return points;
    }

    public static boolean contains(List<Point> points, int x, int z) {
        if (points.isEmpty()) return true;
        Point p = new Point(x, z);
        boolean inside = false;
        for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
            Point a = points.get(j), b = points.get(i);
            if (on(a, b, p)) return true;
            if ((a.z > z) != (b.z > z) && x < (double) (b.x - a.x) * (z - a.z) / (b.z - a.z) + a.x)
                inside = !inside;
        }
        return inside;
    }

    private static long cross(Point a, Point b, Point c) {
        return (long) (b.x - a.x) * (c.z - a.z) - (long) (b.z - a.z) * (c.x - a.x);
    }

    private static boolean on(Point a, Point b, Point p) {
        return cross(a, b, p) == 0 && p.x >= Math.min(a.x, b.x) && p.x <= Math.max(a.x, b.x)
                && p.z >= Math.min(a.z, b.z) && p.z <= Math.max(a.z, b.z);
    }

    private static boolean intersects(Point a, Point b, Point c, Point d) {
        long abC = cross(a, b, c), abD = cross(a, b, d), cdA = cross(c, d, a), cdB = cross(c, d, b);
        return on(a, b, c) || on(a, b, d) || on(c, d, a) || on(c, d, b)
                || Long.signum(abC) != Long.signum(abD) && Long.signum(cdA) != Long.signum(cdB);
    }

    public static JsonArray json(List<Point> points) {
        JsonArray array = new JsonArray();
        for (Point p : points) { JsonArray pair = new JsonArray(); pair.add(p.x); pair.add(p.z); array.add(pair); }
        return array;
    }

    public static List<Point> parse(JsonArray array) {
        if (array.size() > 32) throw new IllegalArgumentException("Too many polygon corners.");
        var points = new java.util.ArrayList<Point>();
        for (JsonElement element : array) {
            JsonArray pair = element.getAsJsonArray();
            if (pair.size() != 2) throw new IllegalArgumentException("A polygon corner needs x and z.");
            points.add(new Point(integer(pair.get(0)), integer(pair.get(1))));
        }
        return validate(points);
    }

    public static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Coordinates must be integers.");
        return value.getAsBigDecimal().intValueExact();
    }
}
