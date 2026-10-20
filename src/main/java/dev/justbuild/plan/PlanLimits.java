package dev.justbuild.plan;

import java.util.List;

/** Caps on what a plan may ask for. maxBlocks is enforced while rasterizing. */
public record PlanLimits(int maxOperations, int maxBlocks, int maxSize) {
    static final int MAX_COORDINATE = 4096;
    static final int MAX_REPEAT = 256;

    /** Checks operation count, scalar sanity, and the overall bounding box; returns that box. */
    public Bounds check(BuildPlan plan) throws PlanException {
        List<Operation> ops = plan.operations();
        if (ops.size() > maxOperations) {
            throw new PlanException("plan has " + ops.size() + " operations; the limit is " + maxOperations);
        }
        Bounds total = null;
        for (int i = 0; i < ops.size(); i++) {
            Operation op = ops.get(i);
            checkScalars(op, i + 1);
            Bounds b = Bounds.of(op);
            total = total == null ? b : total.union(b);
        }
        if (total.sizeX() > maxSize || total.sizeY() > maxSize || total.sizeZ() > maxSize) {
            throw new PlanException("plan is " + total.sizeX() + "x" + total.sizeY() + "x" + total.sizeZ()
                    + "; the limit is " + maxSize + " on each axis");
        }
        return total;
    }

    private void checkScalars(Operation op, int index) throws PlanException {
        String at = "operation " + index;
        for (Cell c : new Cell[] {op.from(), op.to()}) {
            if (c != null && (beyond(c.x()) || beyond(c.y()) || beyond(c.z()))) {
                throw new PlanException(at + " has a coordinate beyond ±" + MAX_COORDINATE);
            }
        }
        if (op.radius() != null && op.radius() > maxSize) {
            throw new PlanException(at + " radius " + op.radius() + " exceeds the size limit " + maxSize);
        }
        if (op.height() != null && op.height() > maxSize) {
            throw new PlanException(at + " height " + op.height() + " exceeds the size limit " + maxSize);
        }
        if (op.radii() != null && (op.radii().x() > maxSize || op.radii().y() > maxSize || op.radii().z() > maxSize)) {
            throw new PlanException(at + " radii exceed the size limit " + maxSize);
        }
        if (op.layers() != null) {
            if (op.layers().size() > maxSize) {
                throw new PlanException(at + " has " + op.layers().size() + " layers; the limit is " + maxSize);
            }
            for (List<String> layer : op.layers()) {
                if (layer.size() > maxSize || layer.stream().anyMatch(row -> row.length() > maxSize)) {
                    throw new PlanException(at + " has a voxel layer larger than " + maxSize);
                }
            }
        }
        if (op.repeat() != null) {
            Cell s = op.repeat().step();
            if (op.repeat().count() < 1) {
                throw new PlanException(at + " repeats " + op.repeat().count() + " times; a repeat needs at least 1");
            }
            if (op.repeat().count() > MAX_REPEAT) {
                throw new PlanException(at + " repeats " + op.repeat().count() + " times; the limit is " + MAX_REPEAT);
            }
            if (Math.abs((long) s.x()) > maxSize || Math.abs((long) s.y()) > maxSize || Math.abs((long) s.z()) > maxSize) {
                throw new PlanException(at + " has a repeat step larger than " + maxSize);
            }
        }
        if (op.mirror() != null && Math.abs(op.mirror().at()) > MAX_COORDINATE) {
            throw new PlanException(at + " mirrors beyond ±" + MAX_COORDINATE);
        }
    }

    private static boolean beyond(int v) {
        return Math.abs((long) v) > MAX_COORDINATE;
    }
}
