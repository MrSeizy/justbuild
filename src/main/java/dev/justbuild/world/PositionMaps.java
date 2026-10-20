package dev.justbuild.world;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/**
 * Unmodifiable copies of maps keyed by block position. Map.copyOf probes linearly, and the hash codes of a box of
 * positions are dense, nearly consecutive integers, so it takes quadratic time: about 0.3 s for 100,000 positions and
 * tens of seconds for 250,000. HashMap spreads hash bits and tree-bins crowded buckets.
 */
public final class PositionMaps {
    private PositionMaps() {
    }

    public static <V> Map<BlockPos, V> frozen(Map<BlockPos, ? extends V> map) {
        return Collections.unmodifiableMap(new HashMap<>(map));
    }
}
