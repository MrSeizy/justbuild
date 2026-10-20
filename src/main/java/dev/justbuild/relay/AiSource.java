package dev.justbuild.relay;

/** Which AI plans a request: the server's active profile, the player's own (in their game), or neither. */
public enum AiSource {
    SERVER, PLAYER, REFUSE;

    /**
     * @param playerAi the server's setting: prefer, require, or off
     * @param player   the request comes from a player (not the console or RCON)
     * @param owner    that player is the singleplayer owner or LAN host, whose game shares the server's computer
     * @param ready    that player's game reported a usable AI with the same relay protocol
     */
    public static AiSource choose(String playerAi, boolean player, boolean owner, boolean ready) {
        if (!player || owner || "off".equals(playerAi)) {
            return SERVER;
        }
        if (ready) {
            return PLAYER;
        }
        return "require".equals(playerAi) ? REFUSE : SERVER;
    }
}
