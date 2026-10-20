package dev.justbuild.world;

import net.minecraft.world.phys.Vec3;

/** Public visual activity only: no prompts, provider details, ownership permissions, or world writes. */
public record BuilderWorksite(String id, BuildRegion area, boolean working) {
    public static final int VIEW_DISTANCE = 96;

    public BuilderWorksite {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || area == null || area.dimension() == null) {
            throw new IllegalArgumentException("Invalid builder worksite");
        }
    }

    public double distanceSquared(Vec3 position) {
        double dx = Math.max(area.min().getX() - position.x, Math.max(0, position.x - area.max().getX() - 1));
        double dy = Math.max(area.min().getY() - position.y, Math.max(0, position.y - area.max().getY() - 2));
        double dz = Math.max(area.min().getZ() - position.z, Math.max(0, position.z - area.max().getZ() - 1));
        return dx * dx + dy * dy + dz * dz;
    }
}
