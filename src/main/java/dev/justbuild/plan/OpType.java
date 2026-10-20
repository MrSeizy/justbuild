package dev.justbuild.plan;

import java.util.Locale;

public enum OpType {
    SET, FILL, LINE, SPHERE, ELLIPSOID, CYLINDER, ROOF, STAIRS, ARCH, VOXELS, REPLACE, FOUNDATION, PORTAL;

    public static OpType parse(String name) throws PlanException {
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PlanException("unknown operation \"" + name + "\"");
        }
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
