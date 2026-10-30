package dev.justbuild.plan;

import java.util.List;

/** Inclusive bounding box in plan coordinates. */
public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    /** Bounds of every cell the operation can touch, including repeats and its mirrored copy. */
    public static Bounds of(Operation op) {
        Bounds b = base(op);
        if (op.repeat() != null && op.repeat().count() > 1) {
            Cell s = op.repeat().step();
            int n = op.repeat().count() - 1;
            b = b.union(b.shift(s.x() * n, s.y() * n, s.z() * n));
        }
        if (op.mirror() != null) {
            b = b.union(b.reflect(op.mirror()));
        }
        return b;
    }

    /** Bounds of every cell the operation can place: of, with a foundation's full depth below its top layer. */
    public static Bounds touched(Operation op) {
        Bounds b = of(op);
        return op.op() != OpType.FOUNDATION ? b
                : new Bounds(b.minX, b.minY - (Rasterizer.FOUNDATION_DEPTH - 1), b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    static Bounds base(Operation op) {
        Cell f = op.from();
        return switch (op.op()) {
            case SET -> new Bounds(f.x(), f.y(), f.z(), f.x(), f.y(), f.z());
            case FILL, LINE, REPLACE -> box(f, op.to());
            case FOUNDATION -> {
                Bounds box = box(f, op.to());
                yield new Bounds(box.minX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ);
            }
            case SPHERE -> {
                int r = reach(op.radius());
                yield new Bounds(f.x() - r, f.y() - r, f.z() - r, f.x() + r, f.y() + r, f.z() + r);
            }
            case ELLIPSOID -> {
                int rx = reach(op.radii().x());
                int ry = reach(op.radii().y());
                int rz = reach(op.radii().z());
                yield new Bounds(f.x() - rx, f.y() - ry, f.z() - rz, f.x() + rx, f.y() + ry, f.z() + rz);
            }
            case CYLINDER -> {
                int r = reach(op.radius());
                yield new Bounds(f.x() - r, f.y(), f.z() - r, f.x() + r, f.y() + op.height() - 1, f.z() + r);
            }
            case ROOF -> {
                Bounds box = box(f, op.to());
                String style = op.style() == null ? "gable" : op.style();
                if ("flat".equals(style)) {
                    yield new Bounds(box.minX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ);
                }
                int pitch = op.pitch() == null ? 1 : op.pitch();
                boolean gambrel = "gambrel".equals(style);
                int top = "spire".equals(style) ? spireTop(op, box) : RoofProfile.top(box.minY, gambrel ? 2 : pitch, op.flare() == Boolean.TRUE, gambrel,
                                roofLayers(op, box.sizeX(), box.sizeZ()));
                // Eaves hang one below; a gable's rim stands one above its ridge.
                boolean eaves = op.eaves() != Boolean.FALSE || op.beam() != null;
                // Only a 45° gable's rim stands above the roof; a steep one follows the roof's profile.
                boolean rim = !"hip".equals(style) && !"spire".equals(style) && !gambrel && op.rim() != Boolean.FALSE
                        && pitch == 1;
                // A finial stands on the ridge (or the rim's cap) and rises one block per entry.
                int finial = op.finial() == null ? 0 : op.finial().size();
                yield new Bounds(box.minX, eaves ? box.minY - 1 : box.minY, box.minZ, box.maxX,
                        (rim ? top + 1 : top) + finial, box.maxZ);
            }
            case STAIRS -> {
                Bounds row = stairsRow(op);
                Cell d = directionStep(op.direction());
                int n = op.height() - 1;
                yield row.union(row.shift(d.x() * n, n, d.z() * n));
            }
            case ARCH -> {
                int r = reach(op.radius());
                int top = f.y() + op.height() + ("pointed".equals(op.style()) ? Rasterizer.pointedPeak(r) : r);
                yield "z".equals(op.axis())
                        ? new Bounds(f.x(), f.y(), f.z() - r, f.x(), top, f.z() + r)
                        : new Bounds(f.x() - r, f.y(), f.z(), f.x() + r, top, f.z());
            }
            case PORTAL -> {
                if ("end".equals(op.style())) {
                    yield new Bounds(f.x() - 2, f.y(), f.z() - 2, f.x() + 2, f.y(), f.z() + 2);
                }
                // The opening plus its frame.
                Bounds open = box(f, op.to());
                boolean alongX = f.z() == op.to().z();
                yield new Bounds(alongX ? open.minX - 1 : open.minX, open.minY - 1, alongX ? open.minZ : open.minZ - 1,
                        alongX ? open.maxX + 1 : open.maxX, open.maxY + 1, alongX ? open.maxZ : open.maxZ + 1);
            }
            case VOXELS -> {
                int rows = 1;
                int length = 1;
                for (List<String> layer : op.layers()) {
                    rows = Math.max(rows, layer.size());
                    for (String row : layer) {
                        length = Math.max(length, row.length());
                    }
                }
                yield new Bounds(f.x(), f.y(), f.z(), f.x() + length - 1, f.y() + op.layers().size() - 1,
                        f.z() + rows - 1);
            }
        };
    }

    static Bounds box(Cell f, Cell t) {
        return new Bounds(
                Math.min(f.x(), t.x()), Math.min(f.y(), t.y()), Math.min(f.z(), t.z()),
                Math.max(f.x(), t.x()), Math.max(f.y(), t.y()), Math.max(f.z(), t.z()));
    }

    /** Farthest whole-block offset a round shape reaches; cells count when within radius + 0.5. */
    static int reach(double radius) {
        return (int) Math.floor(radius + 0.5);
    }

    /** Gable ridge axis: as given, else along the longer side (ties: x). */
    static String ridgeAxis(Operation op, int sizeX, int sizeZ) {
        return op.axis() != null ? op.axis() : sizeX >= sizeZ ? "x" : "z";
    }

    /** A spire's slab cap: one above its peak block. */
    static int spireTop(Operation op, Bounds box) {
        int[] s = RoofProfile.spire(box.minY, op.flare() == Boolean.TRUE, (Math.min(box.sizeX(), box.sizeZ()) + 1) / 2,
                RoofProfile.spireHeight(op.height(), box.sizeX(), box.sizeZ()));
        return s[s.length - 1] + 1;
    }

    static int roofLayers(Operation op, int sizeX, int sizeZ) {
        String style = op.style() == null ? "gable" : op.style();
        return switch (style) {
            case "flat" -> 1;
            case "hip", "spire" -> (Math.min(sizeX, sizeZ) + 1) / 2;
            default -> "x".equals(ridgeAxis(op, sizeX, sizeZ)) ? (sizeZ + 1) / 2 : (sizeX + 1) / 2;
        };
    }

    /** Unit horizontal step for a direction name in the plan frame (north is -z). */
    static Cell directionStep(String direction) {
        return switch (direction) {
            case "north" -> new Cell(0, 0, -1);
            case "south" -> new Cell(0, 0, 1);
            case "east" -> new Cell(1, 0, 0);
            default -> new Cell(-1, 0, 0);
        };
    }

    /** The bottom step row: across x for stairs climbing north/south, across z for east/west, at from's position. */
    static Bounds stairsRow(Operation op) {
        Cell f = op.from();
        Cell t = op.to();
        boolean climbsAlongZ = op.direction().equals("north") || op.direction().equals("south");
        return climbsAlongZ
                ? new Bounds(Math.min(f.x(), t.x()), f.y(), f.z(), Math.max(f.x(), t.x()), f.y(), f.z())
                : new Bounds(f.x(), f.y(), Math.min(f.z(), t.z()), f.x(), f.y(), Math.max(f.z(), t.z()));
    }

    public Bounds union(Bounds o) {
        return new Bounds(
                Math.min(minX, o.minX), Math.min(minY, o.minY), Math.min(minZ, o.minZ),
                Math.max(maxX, o.maxX), Math.max(maxY, o.maxY), Math.max(maxZ, o.maxZ));
    }

    Bounds shift(int dx, int dy, int dz) {
        return new Bounds(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    Bounds reflect(MirrorPlane mirror) {
        int t = mirror.twiceAt();
        return mirror.axis().equals("x")
                ? new Bounds(t - maxX, minY, minZ, t - minX, maxY, maxZ)
                : new Bounds(minX, minY, t - maxZ, maxX, maxY, t - minZ);
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }
}
