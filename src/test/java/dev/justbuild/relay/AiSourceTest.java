package dev.justbuild.relay;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AiSourceTest {
    @Test
    void preferUsesThePlayersAiWhenReady() {
        assertEquals(AiSource.PLAYER, AiSource.choose("prefer", true, false, true));
        assertEquals(AiSource.SERVER, AiSource.choose("prefer", true, false, false));
    }

    @Test
    void requireRefusesPlayersWithoutTheirOwnAi() {
        assertEquals(AiSource.PLAYER, AiSource.choose("require", true, false, true));
        assertEquals(AiSource.REFUSE, AiSource.choose("require", true, false, false));
    }

    @Test
    void offTheConsoleAndTheSingleplayerOwnerUseTheServer() {
        assertEquals(AiSource.SERVER, AiSource.choose("off", true, false, true));
        assertEquals(AiSource.SERVER, AiSource.choose("require", false, false, false), "console and RCON");
        assertEquals(AiSource.SERVER, AiSource.choose("require", true, true, true), "the LAN host's own game");
    }
}
