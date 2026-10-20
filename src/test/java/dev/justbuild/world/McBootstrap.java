package dev.justbuild.world;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Loads vanilla registries once so tests can use real blocks. */
public final class McBootstrap {
    private static boolean done;

    private McBootstrap() {
    }

    public static synchronized void init() {
        if (done) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        done = true;
    }
}
