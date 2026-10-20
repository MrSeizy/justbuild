package dev.justbuild.client;

/**
 * How the game reached the server it is on. A server can transfer players to another address, and Minecraft keeps the
 * original server-list entry for the new connection, so "Use my AI here" (keyed by that entry) would otherwise carry
 * over to wherever a server sends the player.
 */
public final class ConnectionOrigin {
    private static volatile boolean transferred;

    private ConnectionOrigin() {
    }

    /** Called as each connection to a remote server starts. */
    public static void connecting(boolean viaTransfer) {
        transferred = viaTransfer;
    }

    /** Whether the current remote server was reached through another server's transfer. */
    public static boolean transferred() {
        return transferred;
    }
}
