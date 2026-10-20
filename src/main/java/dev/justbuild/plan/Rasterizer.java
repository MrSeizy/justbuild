package dev.justbuild.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Expands operations into cells. Later operations overwrite earlier ones at the same cell. Each operation is applied
 * once per repeat, then once more per repeat reflected if it has a mirror.
 */
public final class Rasterizer {
    public static final String AIR = "minecraft:air";
    static final int FOUNDATION_DEPTH = 32;
    /** The least work a drawing may spend, so plans near the size limit still draw. */
    static final long MIN_WORK = 8_000_000;
    /** A world read costs about as much as drawing eight cells. */
    static final long WORLD_READ = 8;
    /** Radii, heights and coordinates past this are never a real plan (the limits allow ±4096). */
    static final double MAX_EXTENT = 100_000;

    private Rasterizer() {
    }

    public static Map<Cell, BlockRef> rasterize(List<Operation> operations, int maxCells) throws PlanException {
        return rasterize(operations, maxCells, WorldQuery.NONE);
    }

    public static Map<Cell, BlockRef> rasterize(List<Operation> operations, int maxCells, WorldQuery world)
            throws PlanException {
        return rasterize(operations, maxCells, world, TextureSettings.DEFAULT);
    }

    public static Map<Cell, BlockRef> rasterize(List<Operation> operations, int maxCells, WorldQuery world,
            TextureSettings texture) throws PlanException {
        Grid grid = new Grid(maxCells, texture);
        // Portals last, so no other operation can overwrite (and so break) their exact shape.
        List<Operation> ordered = new java.util.ArrayList<>(operations.stream().filter(o -> o.op() != OpType.PORTAL)
                .toList());
        operations.stream().filter(o -> o.op() == OpType.PORTAL).forEach(ordered::add);
        for (Operation op : ordered) {
            int count = op.repeat() == null ? 1 : op.repeat().count();
            Cell step = op.repeat() == null ? new Cell(0, 0, 0) : op.repeat().step();
            // Every shape loops over at most its box, so the box is charged up front, for every repeat and mirror.
            grid.charge(saturatedProduct(work(op), (double) count * (op.mirror() == null ? 1 : 2)));
            for (int i = 0; i < count; i++) {
                apply(op, new Sink(grid, world, step.x() * i, step.y() * i, step.z() * i, null));
            }
            if (op.mirror() != null) {
                for (int i = 0; i < count; i++) {
                    apply(op, new Sink(grid, world, step.x() * i, step.y() * i, step.z() * i, op.mirror()));
                }
            }
        }
        return grid.cells;
    }

    /** Cells one application of {@code op} may visit: its box (a foundation's column depth included). */
    static long work(Operation op) {
        double extent = 0;
        if (op.radius() != null) extent = Math.max(extent, op.radius());
        if (op.height() != null) extent = Math.max(extent, op.height());
        if (op.radii() != null) extent = Math.max(extent, Math.max(op.radii().x(), Math.max(op.radii().y(), op.radii().z())));
        if (!(extent < MAX_EXTENT) || far(op.from()) || far(op.to())) {
            return Long.MAX_VALUE;
        }
        Bounds b = Bounds.base(op);
        double volume = ((double) b.maxX() - b.minX() + 1) * ((double) b.maxY() - b.minY() + 1)
                * ((double) b.maxZ() - b.minZ() + 1);
        if (op.op() == OpType.FOUNDATION) {
            volume = ((double) b.maxX() - b.minX() + 1) * ((double) b.maxZ() - b.minZ() + 1) * FOUNDATION_DEPTH;
        }
        return saturatedProduct(1, volume);
    }

    private static boolean far(Cell c) {
        return c != null && (Math.abs((long) c.x()) > MAX_EXTENT || Math.abs((long) c.y()) > MAX_EXTENT
                || Math.abs((long) c.z()) > MAX_EXTENT);
    }

    private static long saturatedProduct(long a, double b) {
        double product = (double) a * b;
        return product >= Long.MAX_VALUE / 2.0 ? Long.MAX_VALUE / 2 : (long) Math.max(1, product);
    }

    private static void apply(Operation op, Sink sink) throws PlanException {
        switch (op.op()) {
            case SET -> sink.put(op.from().x(), op.from().y(), op.from().z(), BlockRef.of(op.block()));
            case FILL -> fill(sink, op);
            case LINE -> line(sink, op);
            case SPHERE -> sphere(sink, op);
            case ELLIPSOID -> ellipsoid(sink, op);
            case CYLINDER -> cylinder(sink, op);
            case ROOF -> roof(sink, op);
            case STAIRS -> stairs(sink, op);
            case ARCH -> arch(sink, op);
            case VOXELS -> voxels(sink, op);
            case REPLACE -> replace(sink, op);
            case FOUNDATION -> foundation(sink, op);
            case PORTAL -> portal(sink, op);
        }
    }

    /**
     * A portal exactly as Minecraft requires. Nether: an obsidian frame one block around the opening (corners
     * included), and the opening filled with portal blocks whose axis runs along the frame. End: twelve frames around a
     * 3 × 3 middle at the same height, each facing the center, with eyes, and the middle filled with end portal.
     * {@code hollow} leaves it unlit (empty opening; frames without eyes).
     */
    private static void portal(Sink sink, Operation op) throws PlanException {
        boolean lit = !op.hollow();
        Cell f = op.from();
        if ("end".equals(op.style())) {
            for (int d = -1; d <= 1; d++) {
                sink.put(f.x() + d, f.y(), f.z() - 2, endFrame("south", lit));
                sink.put(f.x() + d, f.y(), f.z() + 2, endFrame("north", lit));
                sink.put(f.x() - 2, f.y(), f.z() + d, endFrame("east", lit));
                sink.put(f.x() + 2, f.y(), f.z() + d, endFrame("west", lit));
                for (int e = -1; e <= 1; e++) {
                    sink.put(f.x() + d, f.y(), f.z() + e, lit ? BlockRef.of("minecraft:end_portal") : BlockRef.AIR);
                }
            }
            return;
        }
        Cell t = op.to();
        boolean alongX = f.z() == t.z();
        BlockRef obsidian = BlockRef.of("minecraft:obsidian");
        BlockRef inside = lit ? BlockRef.of("minecraft:nether_portal[axis=" + (alongX ? "x" : "z") + "]") : BlockRef.AIR;
        int a0 = Math.min(alongX ? f.x() : f.z(), alongX ? t.x() : t.z());
        int a1 = Math.max(alongX ? f.x() : f.z(), alongX ? t.x() : t.z());
        int y0 = Math.min(f.y(), t.y());
        int y1 = Math.max(f.y(), t.y());
        int plane = alongX ? f.z() : f.x();
        for (int a = a0 - 1; a <= a1 + 1; a++) {
            for (int y = y0 - 1; y <= y1 + 1; y++) {
                boolean frame = a < a0 || a > a1 || y < y0 || y > y1;
                put(sink, alongX, a, y, plane, frame ? obsidian : inside);
            }
        }
    }

    private static BlockRef endFrame(String facing, boolean eye) {
        return BlockRef.of("minecraft:end_portal_frame[eye=" + eye + ",facing=" + facing + "]");
    }

    private static void fill(Sink sink, Operation op) throws PlanException {
        Bounds b = Bounds.base(op);
        BlockRef block = BlockRef.of(op.block());
        List<BlockRef> mix = op.mix() == null ? List.of() : op.mix().stream().map(BlockRef::of).toList();
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int y = b.minY(); y <= b.maxY(); y++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    boolean face = x == b.minX() || x == b.maxX() || y == b.minY() || y == b.maxY()
                            || z == b.minZ() || z == b.maxZ();
                    int pick = sink.grid.texture.pick(x * 31 + y, z, op.block(), op.mix());
                    BlockRef here = pick < mix.size() ? mix.get(pick) : block;
                    sink.put(x, y, z, !op.hollow() || face ? here : BlockRef.AIR);
                }
            }
        }
    }

    /** {@code block}, or one of the operation's "mix" blocks at this cell. */
    private static BlockRef mixed(Sink sink, Operation op, BlockRef block, int x, int y, int z) {
        if (op.mix() == null) {
            return block;
        }
        int pick = sink.grid.texture.pick(x * 31 + y, z, op.block(), op.mix());
        return pick < op.mix().size() ? BlockRef.of(op.mix().get(pick)) : block;
    }

    private static void line(Sink sink, Operation op) throws PlanException {
        Cell f = op.from();
        Cell t = op.to();
        int dx = t.x() - f.x();
        int dy = t.y() - f.y();
        int dz = t.z() - f.z();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        BlockRef block = BlockRef.of(op.block());
        for (int i = 0; i <= steps; i++) {
            double s = steps == 0 ? 0 : (double) i / steps;
            sink.put(f.x() + (int) Math.round(dx * s), f.y() + (int) Math.round(dy * s), f.z() + (int) Math.round(dz * s),
                    block);
        }
    }

    private static void sphere(Sink sink, Operation op) throws PlanException {
        double limit = (op.radius() + 0.5) * (op.radius() + 0.5);
        int r = Bounds.reach(op.radius());
        Cell c = op.from();
        BlockRef block = BlockRef.of(op.block());
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!inSphere(dx, dy, dz, limit) || !keepsHalf(op.style(), dy)) {
                        continue;
                    }
                    boolean shell = !inSphere(dx + 1, dy, dz, limit) || !inSphere(dx - 1, dy, dz, limit)
                            || !inSphere(dx, dy + 1, dz, limit) || !inSphere(dx, dy - 1, dz, limit)
                            || !inSphere(dx, dy, dz + 1, limit) || !inSphere(dx, dy, dz - 1, limit);
                    sink.put(c.x() + dx, c.y() + dy, c.z() + dz, !op.hollow() || shell
                            ? mixed(sink, op, block, c.x() + dx, c.y() + dy, c.z() + dz) : BlockRef.AIR);
                }
            }
        }
    }

    private static void ellipsoid(Sink sink, Operation op) throws PlanException {
        Radii radii = op.radii();
        double lx = (radii.x() + 0.5) * (radii.x() + 0.5);
        double ly = (radii.y() + 0.5) * (radii.y() + 0.5);
        double lz = (radii.z() + 0.5) * (radii.z() + 0.5);
        int rx = Bounds.reach(radii.x());
        int ry = Bounds.reach(radii.y());
        int rz = Bounds.reach(radii.z());
        Cell c = op.from();
        BlockRef block = BlockRef.of(op.block());
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dy = -ry; dy <= ry; dy++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    if (!inEllipsoid(dx, dy, dz, lx, ly, lz) || !keepsHalf(op.style(), dy)) {
                        continue;
                    }
                    boolean shell = !inEllipsoid(dx + 1, dy, dz, lx, ly, lz) || !inEllipsoid(dx - 1, dy, dz, lx, ly, lz)
                            || !inEllipsoid(dx, dy + 1, dz, lx, ly, lz) || !inEllipsoid(dx, dy - 1, dz, lx, ly, lz)
                            || !inEllipsoid(dx, dy, dz + 1, lx, ly, lz) || !inEllipsoid(dx, dy, dz - 1, lx, ly, lz);
                    sink.put(c.x() + dx, c.y() + dy, c.z() + dz, !op.hollow() || shell
                            ? mixed(sink, op, block, c.x() + dx, c.y() + dy, c.z() + dz) : BlockRef.AIR);
                }
            }
        }
    }

    private static void cylinder(Sink sink, Operation op) throws PlanException {
        double limit = (op.radius() + 0.5) * (op.radius() + 0.5);
        int r = Bounds.reach(op.radius());
        Cell c = op.from();
        BlockRef block = BlockRef.of(op.block());
        for (int dy = 0; dy < op.height(); dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (!inCircle(dx, dz, limit)) {
                        continue;
                    }
                    boolean wall = !inCircle(dx + 1, dz, limit) || !inCircle(dx - 1, dz, limit)
                            || !inCircle(dx, dz + 1, limit) || !inCircle(dx, dz - 1, limit);
                    sink.put(c.x() + dx, c.y() + dy, c.z() + dz, !op.hollow() || wall
                            ? mixed(sink, op, block, c.x() + dx, c.y() + dy, c.z() + dz) : BlockRef.AIR);
                }
            }
        }
    }

    /**
     * Roofs built the way hand-made Minecraft roofs are. Each course is a stair; a steep roof (pitch 2) is thick, with
     * an upside-down stair and a full block under each stair, and a full ridge with a slab cap. A gable's ends get a
     * rim (barge board) in the "edge" block: on a steep roof the roof's own profile, on a 45° roof one block higher
     * and two thick; the "trim" triangle is set one block back under it; eaves get upside-down stairs underneath; hips get their corners in the edge
     * block. "flare" starts with a slab course so the eaves curve out, and "mix" scatters a second stairs block through
     * the surface. "rim" and "eaves" false turn those off for clean edges; without a rim the triangle is flush.
     */
    private static void roof(Sink sink, Operation op) throws PlanException {
        Bounds b = Bounds.box(op.from(), op.to());
        Slopes s = new Slopes(op.block());
        Slopes edge = op.edge() == null ? s : new Slopes(op.edge());
        List<Slopes> mix = op.mix() == null ? List.of() : op.mix().stream().map(Slopes::new).toList();
        int pitch = op.pitch() == null ? 1 : op.pitch();
        boolean flare = op.flare() == Boolean.TRUE;
        String style = op.style() == null ? "gable" : op.style();
        BlockRef trim = op.trim() == null ? null : BlockRef.of(op.trim());
        Surface surface = new Surface(s, mix, sink.grid.texture, op);
        switch (style) {
            case "flat" -> {
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        sink.put(x, b.minY(), z, surface.at(x, z).ridge);
                    }
                }
            }
            case "hip" -> hip(sink, op, b, pitch, flare, surface, edge, op.edge() != null, null);
            case "spire" -> hip(sink, op, b, 2, flare, surface, edge, op.edge() != null,
                    RoofProfile.spire(b.minY(), flare, (Math.min(b.sizeX(), b.sizeZ()) + 1) / 2,
                            RoofProfile.spireHeight(op.height(), b.sizeX(), b.sizeZ())));
            case "gambrel" -> gable(sink, op, b, 2, flare, surface, edge, op.edge() != null, trim, true);
            default -> gable(sink, op, b, pitch, flare, surface, edge, op.edge() != null, trim, false);
        }
    }

    /** The roof block, or one of the "mix" blocks (one in n + 2 columns each), chosen by position. */
    private record Surface(Slopes main, List<Slopes> mix, TextureSettings texture, Operation op) {
        Slopes at(int a, int c) {
            int pick = texture.pick(a, c, op.block(), op.mix());
            return pick < mix.size() ? mix.get(pick) : main;
        }
    }

    private static void gable(Sink sink, Operation op, Bounds b, int pitch, boolean flare, Surface surface,
            Slopes edge, boolean edged, BlockRef trim, boolean gambrel) throws PlanException {
        boolean alongX = "x".equals(Bounds.ridgeAxis(op, b.sizeX(), b.sizeZ()));
        // Work in (along, across): along follows the ridge, across climbs from both eaves to the ridge.
        int a0 = alongX ? b.minX() : b.minZ();
        int a1 = alongX ? b.maxX() : b.maxZ();
        int c0 = alongX ? b.minZ() : b.minX();
        int c1 = alongX ? b.maxZ() : b.maxX();
        int y0 = b.minY();
        String upFromLow = alongX ? "south" : "east";
        String upFromHigh = alongX ? "north" : "west";
        // Steep and gambrel rims follow the roof's own (thick) profile; a 45° rim stands one block proud.
        boolean steep = pitch >= 2;
        int layers = (c1 - c0 + 2) / 2;
        boolean rim = op.rim() != Boolean.FALSE && a1 - a0 >= 2;
        int fieldA0 = rim ? a0 + 1 : a0;
        int fieldA1 = rim ? a1 - 1 : a1;

        if (op.beam() != null) {
            // A log along each eave instead of eave stairs, and across each gable under the trim triangle.
            BlockRef along = BlockRef.of(BlockSpecs.withAxis(op.beam(), alongX ? "x" : "z"));
            BlockRef across = BlockRef.of(BlockSpecs.withAxis(op.beam(), alongX ? "z" : "x"));
            for (int a = a0; a <= a1; a++) {
                put(sink, alongX, a, y0 - 1, c0, along);
                put(sink, alongX, a, y0 - 1, c1, along);
            }
            for (int c = c0 + 1; rim && c <= c1 - 1; c++) {
                put(sink, alongX, fieldA0, y0 - 1, c, across);
                put(sink, alongX, fieldA1, y0 - 1, c, across);
            }
        } else {
            for (int a = a0; op.eaves() != Boolean.FALSE && a <= a1; a++) {
                put(sink, alongX, a, y0 - 1, c0, edge.under(upFromLow));
                if (c1 > c0) {
                    put(sink, alongX, a, y0 - 1, c1, edge.under(upFromHigh));
                }
            }
        }
        if (pitch == 0) {
            lowGable(sink, op, alongX, a0, a1, c0, c1, y0, rim, fieldA0, fieldA1, surface, edge, trim);
            return;
        }
        int previous = Integer.MAX_VALUE;
        // Where the finial stands at each end of the ridge: on the ridge's full block, in place of its cap.
        int finialC = c0;
        int finialY = y0;
        for (int k = 0; ; k++) {
            int lo = c0 + k;
            int hi = c1 - k;
            if (lo > hi) {
                break;
            }
            int stair = RoofProfile.stairY(y0, pitch, flare, gambrel, layers, k);
            // A course that climbs two blocks reaches down to the course below it: an upside-down stair there, full
            // blocks between. Its ridge is full blocks with a slab cap.
            boolean thick = previous < stair - 1;
            boolean ridge = lo == hi;
            if (ridge && thick) {
                // The peak rises one block above the last course, as hand-built steep roofs do; a full two-block
                // step there stands up like a post.
                stair = previous + 1;
            }
            int low = thick ? previous : stair;
            boolean slab = flare && k == 0;
            // A 45° roof's last course is the ridge cap, in the edge block when one is given; a steep roof keeps its
            // block up to the ridge and gets a slab cap in the edge block.
            boolean capCourse = edged && !thick && (ridge || lo + 1 == hi);
            for (int a = fieldA0; a <= fieldA1; a++) {
                Slopes left = capCourse ? edge : surface.at(a, lo);
                Slopes right = capCourse ? edge : surface.at(a, hi);
                if (ridge) {
                    ridgeColumn(sink, alongX, a, low, stair, lo, left, edged ? edge : left, thick, false);
                } else {
                    slopeColumn(sink, alongX, a, low, stair, lo, left, upFromLow, upFromHigh, slab);
                    slopeColumn(sink, alongX, a, low, stair, hi, right, upFromHigh, upFromLow, slab);
                    if (thick && lo + 1 == hi) {
                        put(sink, alongX, a, stair + 1, lo, (edged ? edge : left).ridge);
                        put(sink, alongX, a, stair + 1, hi, (edged ? edge : right).ridge);
                    }
                }
            }
            if (rim && (steep || gambrel)) {
                // Steep roofs are already thick, so the barge board is the roof's own profile in the edge block.
                for (int a : new int[] {a0, a1}) {
                    if (ridge) {
                        ridgeColumn(sink, alongX, a, low, stair, lo, edge, edge, thick, false);
                    } else {
                        slopeColumn(sink, alongX, a, low, stair, lo, edge, upFromLow, upFromHigh, slab);
                        slopeColumn(sink, alongX, a, low, stair, hi, edge, upFromHigh, upFromLow, slab);
                        if (thick && lo + 1 == hi) {
                            put(sink, alongX, a, stair + 1, lo, edge.ridge);
                            put(sink, alongX, a, stair + 1, hi, edge.ridge);
                        }
                    }
                }
            } else if (rim) {
                // One block above the roof and two thick: a stair on top of an upside-down stair facing down the
                // slope, so the rim's underside is a smooth diagonal.
                for (int a : new int[] {a0, a1}) {
                    if (ridge) {
                        int capY = steep ? stair + 1 : stair;
                        for (int y = low; y <= capY; y++) {
                            put(sink, alongX, a, y, lo, steep ? edge.body : edge.topSlab());
                        }
                        put(sink, alongX, a, capY + 1, lo, edge.ridge);
                    } else if (slab) {
                        put(sink, alongX, a, stair, lo, edge.under(upFromHigh));
                        put(sink, alongX, a, stair, hi, edge.under(upFromLow));
                        put(sink, alongX, a, stair + 1, lo, edge.ridge);
                        put(sink, alongX, a, stair + 1, hi, edge.ridge);
                    } else {
                        put(sink, alongX, a, low, lo, edge.under(upFromHigh));
                        put(sink, alongX, a, low, hi, edge.under(upFromLow));
                        for (int y = low + 1; y <= stair; y++) {
                            put(sink, alongX, a, y, lo, edge.body);
                            put(sink, alongX, a, y, hi, edge.body);
                        }
                        put(sink, alongX, a, stair + 1, lo, edge.facing(upFromLow));
                        put(sink, alongX, a, stair + 1, hi, edge.facing(upFromHigh));
                    }
                }
            }
            if (ridge || lo + 1 == hi) {
                finialC = lo;
                finialY = thick ? stair + 1 : rim && !(steep || gambrel) ? stair + (ridge ? 1 : 2)
                        : stair + (ridge ? 0 : 1);
            }
            if (trim != null && !ridge) {
                for (int c = lo + 1; c <= hi - 1; c++) {
                    for (int y = low; y <= stair; y++) {
                        put(sink, alongX, fieldA0, y, c, trim);
                        put(sink, alongX, fieldA1, y, c, trim);
                    }
                }
            }
            previous = stair;
            if (ridge) {
                break;
            }
        }
        if (op.finial() != null) {
            for (int a : new int[] {a0, a1}) {
                for (int i = 0; i < op.finial().size(); i++) {
                    put(sink, alongX, a, finialY + i, finialC, BlockRef.of(op.finial().get(i)));
                }
            }
        }
    }

    /**
     * A low gable (pitch 0), as on classical temples and Mediterranean houses: it climbs half a block per course,
     * alternating a slab and a full block, with a full block under each slab so the roof is closed. The rim is the
     * same profile in the edge block.
     */
    private static void lowGable(Sink sink, Operation op, boolean alongX, int a0, int a1, int c0, int c1, int y0,
            boolean rim, int fieldA0, int fieldA1, Surface surface, Slopes edge, BlockRef trim) throws PlanException {
        int finialC = c0;
        int finialY = y0;
        for (int k = 0; ; k++) {
            int lo = c0 + k;
            int hi = c1 - k;
            if (lo > hi) {
                break;
            }
            int h = y0 + k / 2;
            for (int a = fieldA0; a <= fieldA1; a++) {
                lowColumn(sink, alongX, a, lo, h, k, surface.at(a, lo));
                lowColumn(sink, alongX, a, hi, h, k, surface.at(a, hi));
            }
            for (int a : rim ? new int[] {a0, a1} : new int[0]) {
                lowColumn(sink, alongX, a, lo, h, k, edge);
                lowColumn(sink, alongX, a, hi, h, k, edge);
            }
            if (trim != null) {
                for (int c = lo + 1; c <= hi - 1; c++) {
                    for (int y = Math.max(y0, h - 1); y <= h; y++) {
                        put(sink, alongX, fieldA0, y, c, trim);
                        put(sink, alongX, fieldA1, y, c, trim);
                    }
                }
            }
            if (lo >= hi - 1) {
                finialC = lo;
                // In place of a top slab (a full block is under it), or on a full block.
                finialY = k % 2 == 0 ? h : h + 1;
                break;
            }
        }
        if (op.finial() != null) {
            for (int a : new int[] {a0, a1}) {
                for (int i = 0; i < op.finial().size(); i++) {
                    put(sink, alongX, a, finialY + i, finialC, BlockRef.of(op.finial().get(i)));
                }
            }
        }
    }

    /** Course {@code k} of a low roof at height {@code h}: a slab (over a full block) on even courses, else a full block. */
    private static void lowColumn(Sink sink, boolean alongX, int along, int across, int h, int k, Slopes slopes)
            throws PlanException {
        if (k % 2 == 0) {
            if (k > 0) {
                put(sink, alongX, along, h - 1, across, slopes.body);
            }
            put(sink, alongX, along, h, across, slopes.ridge);
        } else {
            put(sink, alongX, along, h, across, slopes.body);
        }
    }

    /** {@code spire}: the stair height of each ring for a spire (see RoofProfile.spire), or null for a hip roof. */
    private static void hip(Sink sink, Operation op, Bounds b, int pitch, boolean flare, Surface surface,
            Slopes edge, boolean edged, int[] spire) throws PlanException {
        int x0 = b.minX();
        int x1 = b.maxX();
        int z0 = b.minZ();
        int z1 = b.maxZ();
        int y0 = b.minY();
        boolean steep = pitch >= 2;
        boolean eaves = op.eaves() != Boolean.FALSE && op.beam() == null;
        if (op.beam() != null) {
            BlockRef alongX = BlockRef.of(BlockSpecs.withAxis(op.beam(), "x"));
            BlockRef alongZ = BlockRef.of(BlockSpecs.withAxis(op.beam(), "z"));
            for (int x = x0; x <= x1; x++) {
                sink.put(x, y0 - 1, z0, alongX);
                sink.put(x, y0 - 1, z1, alongX);
            }
            for (int z = z0 + 1; z <= z1 - 1; z++) {
                sink.put(x0, y0 - 1, z, alongZ);
                sink.put(x1, y0 - 1, z, alongZ);
            }
        }
        for (int x = x0; eaves && x <= x1; x++) {
            sink.put(x, y0 - 1, z0, edge.under("south"));
            sink.put(x, y0 - 1, z1, edge.under("north"));
        }
        for (int z = z0 + 1; eaves && z <= z1 - 1; z++) {
            sink.put(x0, y0 - 1, z, edge.under("east"));
            sink.put(x1, y0 - 1, z, edge.under("west"));
        }
        int previous = Integer.MAX_VALUE;
        for (int k = 0; pitch == 0; k++) {
            // A low hip: rings that climb half a block each, as in lowGable.
            int ax = x0 + k;
            int bx = x1 - k;
            int az = z0 + k;
            int bz = z1 - k;
            if (ax > bx || az > bz) {
                break;
            }
            int h = y0 + k / 2;
            for (int x = ax; x <= bx; x++) {
                for (int z = az; z <= bz; z++) {
                    if (x == ax || x == bx || z == az || z == bz) {
                        boolean corner = (x == ax || x == bx) && (z == az || z == bz);
                        lowColumn(sink, true, x, z, h, k, corner && edged ? edge : surface.at(x, z));
                    }
                }
            }
            if ((ax >= bx - 1 || az >= bz - 1) && op.finial() != null) {
                int y = k % 2 == 0 ? h : h + 1;
                for (int[] end : new int[][] {{ax, az}, {bx, bz}}) {
                    for (int i = 0; i < op.finial().size(); i++) {
                        sink.put(end[0], y + i, end[1], BlockRef.of(op.finial().get(i)));
                    }
                }
            }
        }
        for (int k = 0; pitch > 0; k++) {
            int ax = x0 + k;
            int bx = x1 - k;
            int az = z0 + k;
            int bz = z1 - k;
            if (ax > bx || az > bz) {
                break;
            }
            int stair = spire != null ? spire[Math.min(k, spire.length - 1)] : RoofProfile.stairY(y0, pitch, flare,
                    false, 0, k);
            int low = steep && previous < stair ? previous : stair;
            boolean slab = flare && k == 0;
            if (ax == bx || az == bz) {
                for (int x = ax; x <= bx; x++) {
                    for (int z = az; z <= bz; z++) {
                        Slopes body = steep || !edged ? surface.at(x, z) : edge;
                        ridgeColumn(sink, true, x, low, stair, z, body, edged ? edge : body, steep, true);
                    }
                }
                if (op.finial() != null) {
                    // On each end of the ridge line (or the peak), on the ridge's full block in place of its cap.
                    int y = steep ? stair + 1 : stair;
                    for (int[] end : new int[][] {{ax, az}, {bx, bz}}) {
                        for (int i = 0; i < op.finial().size(); i++) {
                            sink.put(end[0], y + i, end[1], BlockRef.of(op.finial().get(i)));
                        }
                    }
                }
                break;
            }
            for (int x = ax; x <= bx; x++) {
                boolean corner = x == ax || x == bx;
                slopeColumn(sink, true, x, low, stair, az, corner ? edge : surface.at(x, az), "south", "north", slab);
                slopeColumn(sink, true, x, low, stair, bz, corner ? edge : surface.at(x, bz), "north", "south", slab);
            }
            for (int z = az + 1; z <= bz - 1; z++) {
                slopeColumn(sink, true, ax, low, stair, z, surface.at(ax, z), "east", "west", slab);
                slopeColumn(sink, true, bx, low, stair, z, surface.at(bx, z), "west", "east", slab);
            }
            previous = stair;
        }
    }

    /**
     * One course of a slope: the stair at {@code stair}; on a steep roof also an upside-down stair at {@code low}
     * (facing down the slope) and full blocks between. A flared roof's first course is a slab.
     */
    private static void slopeColumn(Sink sink, boolean alongX, int along, int low, int stair, int across,
            Slopes slopes, String up, String down, boolean slab) throws PlanException {
        if (slab) {
            put(sink, alongX, along, stair, across, slopes.ridge);
            return;
        }
        if (low < stair) {
            put(sink, alongX, along, low, across, slopes.under(down));
            for (int y = low + 1; y < stair; y++) {
                put(sink, alongX, along, y, across, slopes.body);
            }
        }
        put(sink, alongX, along, stair, across, slopes.facing(up));
    }

    /**
     * The ridge: a slab of {@code cap}. On a steep roof, full blocks down to the course below; a hip's peak then gets a
     * slab cap, and a gable's ridge ends in a full block of {@code cap} (its edge color) instead.
     */
    private static void ridgeColumn(Sink sink, boolean alongX, int along, int low, int stair, int across,
            Slopes slopes, Slopes cap, boolean steep, boolean slabCap) throws PlanException {
        if (!steep) {
            put(sink, alongX, along, stair, across, cap.ridge);
            return;
        }
        for (int y = low; y < stair; y++) {
            put(sink, alongX, along, y, across, slopes.body);
        }
        put(sink, alongX, along, stair, across, slabCap ? slopes.body : cap.body);
        if (slabCap) {
            put(sink, alongX, along, stair + 1, across, cap.ridge);
        }
    }

    private static void put(Sink sink, boolean alongX, int along, int y, int across, BlockRef ref) throws PlanException {
        if (alongX) {
            sink.put(along, y, across, ref);
        } else {
            sink.put(across, y, along, ref);
        }
    }

    private static void stairs(Sink sink, Operation op) throws PlanException {
        Bounds row = Bounds.stairsRow(op);
        Cell d = Bounds.directionStep(op.direction());
        Slopes s = new Slopes(op.block());
        BlockRef step = s.facing(op.direction());
        BlockRef trim = op.trim() == null ? null : BlockRef.of(op.trim());
        int y0 = row.minY();
        for (int i = 0; i < op.height(); i++) {
            for (int x = row.minX(); x <= row.maxX(); x++) {
                for (int z = row.minZ(); z <= row.maxZ(); z++) {
                    int px = x + d.x() * i;
                    int pz = z + d.z() * i;
                    if (trim != null) {
                        for (int y = y0; y < y0 + i; y++) {
                            sink.put(px, y, pz, trim);
                        }
                    }
                    sink.put(px, y0 + i, pz, step);
                }
            }
        }
    }

    private static void arch(Sink sink, Operation op) throws PlanException {
        int r = Bounds.reach(op.radius());
        boolean pointed = "pointed".equals(op.style());
        // Pointed arches mark themselves with a negative limit (see inArch).
        double limit = pointed ? -1 : (op.radius() + 0.5) * (op.radius() + 0.5);
        int h = op.height();
        boolean alongZ = "z".equals(op.axis());
        Cell f = op.from();
        BlockRef block = BlockRef.of(op.block());
        int top = h + (pointed ? pointedPeak(r) : r);
        for (int u = -r; u <= r; u++) {
            for (int v = 0; v <= top; v++) {
                if (!inArch(u, v, r, h, limit)) {
                    continue;
                }
                BlockRef ref = block;
                if (op.hollow()) {
                    boolean edge = !inArch(u + 1, v, r, h, limit) || !inArch(u - 1, v, r, h, limit)
                            || !inArch(u, v + 1, r, h, limit) || (v > 0 && !inArch(u, v - 1, r, h, limit));
                    ref = edge ? block : BlockRef.AIR;
                }
                if (alongZ) {
                    sink.put(f.x(), f.y() + v, f.z() + u, ref);
                } else {
                    sink.put(f.x() + u, f.y() + v, f.z(), ref);
                }
            }
        }
    }

    /**
     * A gothic (equilateral) arch above its springing line: inside both arcs whose radius is the opening's width, each
     * centred on the opposite edge of the opening. Cell centres are tested.
     */
    private static boolean inPointed(int u, int up, int r) {
        double span = 2 * r + 1;
        double edge = r + 0.5;
        double y = up + 0.5;
        return (u + edge) * (u + edge) + y * y <= span * span && (u - edge) * (u - edge) + y * y <= span * span;
    }

    /** The top row of a pointed arch above its springing line; it always ends in a single block. */
    static int pointedPeak(int r) {
        int up = 0;
        while (inPointed(0, up + 1, r)) {
            up++;
        }
        return r > 0 && inPointed(1, up, r) ? up + 1 : up;
    }

    private static boolean inArch(int u, int v, int r, int h, double limit) {
        if (v < 0 || Math.abs(u) > r) {
            return false;
        }
        if (v < h) {
            return true;
        }
        int up = v - h;
        if (limit < 0) {
            return inPointed(u, up, r) || u == 0 && up == pointedPeak(r);
        }
        return u * u + up * up <= limit;
    }

    private static void voxels(Sink sink, Operation op) throws PlanException {
        Cell f = op.from();
        List<List<String>> layers = op.layers();
        for (int i = 0; i < layers.size(); i++) {
            List<String> rows = layers.get(i);
            for (int r = 0; r < rows.size(); r++) {
                String row = rows.get(r);
                for (int c = 0; c < row.length(); c++) {
                    char ch = row.charAt(c);
                    if (ch == '.' || ch == ' ') {
                        continue;
                    }
                    sink.put(f.x() + c, f.y() + i, f.z() + r, BlockRef.of(op.palette().get(ch)));
                }
            }
        }
    }

    private static void replace(Sink sink, Operation op) throws PlanException {
        Bounds b = Bounds.base(op);
        BlockRef block = BlockRef.of(op.block());
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int y = b.minY(); y <= b.maxY(); y++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    BlockRef planned = sink.planned(x, y, z);
                    String current = planned != null ? planned.spec() : sink.worldBlock(x, y, z);
                    if (BlockSpecs.sameBlock(current, op.target())) {
                        sink.put(x, y, z, block);
                    }
                }
            }
        }
    }

    private static void foundation(Sink sink, Operation op) throws PlanException {
        Bounds b = Bounds.base(op);
        BlockRef block = BlockRef.of(op.block());
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int d = 0; d < FOUNDATION_DEPTH; d++) {
                    int y = b.minY() - d;
                    BlockRef planned = sink.planned(x, y, z);
                    boolean solid = planned != null ? !BlockSpecs.sameBlock(planned.spec(), AIR) : sink.worldSolid(x, y, z);
                    if (solid) {
                        break;
                    }
                    sink.put(x, y, z, block);
                }
            }
        }
    }

    private static boolean keepsHalf(String style, int dy) {
        return style == null || style.equals("full") || (style.equals("dome") ? dy >= 0 : dy <= 0);
    }

    private static boolean inSphere(int dx, int dy, int dz, double limit) {
        return dx * dx + dy * dy + dz * dz <= limit;
    }

    private static boolean inEllipsoid(int dx, int dy, int dz, double lx, double ly, double lz) {
        return dx * dx / lx + dy * dy / ly + dz * dz / lz <= 1.0;
    }

    private static boolean inCircle(int dx, int dz, double limit) {
        return dx * dx + dz * dz <= limit;
    }

    /** Stairs blocks get a facing; anything else is used as-is. The ridge is the matching slab. */
    private static final class Slopes {
        final String block;
        final boolean stairs;
        final BlockRef ridge;
        /** The full block the stairs are cut from, for the inside of thick roofs. */
        final BlockRef body;

        Slopes(String block) {
            this.block = block;
            this.stairs = BlockSpecs.isStairs(block);
            this.ridge = BlockRef.of(stairs ? BlockSpecs.slabFor(block) : block);
            this.body = BlockRef.of(stairs ? BlockSpecs.fullFor(block) : block);
        }

        BlockRef facing(String direction) {
            return stair(direction, "bottom");
        }

        /** An upside-down stair, for eaves, rims, and steep slopes. */
        BlockRef under(String direction) {
            return stair(direction, "top");
        }

        /** The slab in the upper half of the block (or the block itself for a non-stair). */
        BlockRef topSlab() {
            if (!stairs) {
                return BlockRef.of(block);
            }
            return BlockRef.of(BlockSpecs.withProperties(BlockSpecs.slabFor(block), Map.of("type", "top")));
        }

        private BlockRef stair(String direction, String half) {
            if (!stairs) {
                return BlockRef.of(block);
            }
            Map<String, String> props = new LinkedHashMap<>();
            props.put("facing", direction);
            props.put("half", half);
            return BlockRef.of(BlockSpecs.withProperties(block, props));
        }
    }

    /** Writes and reads cells for one application of an operation: shifted by a repeat, optionally reflected. */
    private static final class Sink {
        final Grid grid;
        final WorldQuery world;
        final int dx;
        final int dy;
        final int dz;
        final MirrorPlane mirror;

        Sink(Grid grid, WorldQuery world, int dx, int dy, int dz, MirrorPlane mirror) {
            this.grid = grid;
            this.world = world;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.mirror = mirror;
        }

        Cell map(int x, int y, int z) {
            int mx = x + dx;
            int my = y + dy;
            int mz = z + dz;
            if (mirror != null) {
                if (mirror.axis().equals("x")) {
                    mx = mirror.twiceAt() - mx;
                } else {
                    mz = mirror.twiceAt() - mz;
                }
            }
            return new Cell(mx, my, mz);
        }

        void put(int x, int y, int z, BlockRef ref) throws PlanException {
            boolean flipX = mirror != null && mirror.axis().equals("x");
            boolean flipZ = mirror != null && mirror.axis().equals("z");
            grid.put(map(x, y, z), ref.flipped(flipX, flipZ));
        }

        BlockRef planned(int x, int y, int z) {
            return grid.cells.get(map(x, y, z));
        }

        String worldBlock(int x, int y, int z) throws PlanException {
            grid.charge(WORLD_READ);
            return world.blockId(map(x, y, z));
        }

        boolean worldSolid(int x, int y, int z) throws PlanException {
            grid.charge(WORLD_READ);
            return world.isSolid(map(x, y, z));
        }
    }

    /**
     * Cells and world reads one drawing may spend: 16 per block allowed, at least {@link #MIN_WORK}. Stored builds are
     * recomputed with no block limit and so no work limit; they passed this when they were planned.
     */
    private static final class Grid {
        final Map<Cell, BlockRef> cells = new LinkedHashMap<>();
        final int maxCells;
        final long maxWork;
        long work;

        final TextureSettings texture;

        Grid(int maxCells, TextureSettings texture) {
            this.texture = texture;
            this.maxCells = maxCells;
            this.maxWork = maxCells == Integer.MAX_VALUE ? Long.MAX_VALUE : Math.max(16L * maxCells, MIN_WORK);
        }

        void charge(long units) throws PlanTooMuchWorkException {
            if (maxWork == Long.MAX_VALUE) return;
            work = units >= maxWork - work ? maxWork + 1 : work + units;
            if (work > maxWork) throw new PlanTooMuchWorkException();
        }

        void put(Cell cell, BlockRef ref) throws PlanException {
            cells.put(cell, ref);
            if (cells.size() > maxCells) {
                throw new PlanTooLargeException(maxCells);
            }
        }
    }
}
