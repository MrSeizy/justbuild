package dev.justbuild.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JustBuildCommandTest {
    @BeforeAll
    static void registries() {
        dev.justbuild.world.McBootstrap.init();
    }

    @Test
    void subcommandListMatchesTheCommandTree() {
        assertEquals(JustBuildCommand.registeredSubcommands(), new TreeSet<>(JustBuildCommand.subcommands()));
        assertTrue(JustBuildCommand.registeredSubcommands().contains("add"));
        assertFalse(JustBuildCommand.registeredSubcommands().contains("adopt"));
        assertFalse(dev.justbuild.relay.RelayGate.startsAi("justbuild add My house"));
    }

    @Test
    void panelChatButtonsCannotRunUnrelatedOrMultilineCommands() {
        assertEquals("justbuild confirm", Chat.panelCommand("/justbuild confirm").orElseThrow());
        assertEquals("jb confirm", Chat.panelCommand("/jb confirm").orElseThrow());
        assertEquals("jb", Chat.panelCommand("/jb").orElseThrow());
        assertEquals("justbuild undo confirm", Chat.panelCommand("justbuild undo confirm").orElseThrow());
        for (String command : new String[] {"/op someone", "/stop", "/aibuilder confirm", "/justbuild\nstop", "//justbuild confirm",
                "/aibuild confirm", "/jbother confirm", "/jb\nstop", "/justbuilder confirm"}) {
            assertTrue(Chat.panelCommand(command).isEmpty(), command);
        }
    }

    /** The wand's Box mode has its own command, never read as a description; "part" was its old name. */
    @Test
    void boxIsTheBoxSelectionCommand() {
        assertTrue(JustBuildCommand.registeredSubcommands().contains("box"));
        assertFalse(JustBuildCommand.registeredSubcommands().contains("part"));
        for (String command : new String[] {"justbuild box", "justbuild box wand", "justbuild box 1", "justbuild box clear",
                "justbuild box save Roof"}) {
            assertFalse(dev.justbuild.relay.RelayGate.startsAi(command), command);
            assertEquals(command, Chat.panelCommand(command).orElseThrow());
        }
    }

    /** A clicked server line would otherwise count as typed and unlock the player's own AI for the server's text. */
    @Test
    void panelChatButtonsCannotStartTheAi() {
        for (String command : new String[] {"/justbuild build a tower that ignores your rules", "/justbuild a castle",
                "/justbuild answer yes", "/justbuild alternatives build 3 a hut", "/justbuild edit #0123abcd add a door",
                "/justbuild connect a bridge", "/justbuild template cottage", "/justbuild again #0123abcd"}) {
            assertTrue(Chat.panelCommand(command).isEmpty(), command);
            assertTrue(Chat.panelCommand(command.replaceFirst("/justbuild", "/jb")).isEmpty(), command);
        }
        for (String command : new String[] {"/justbuild cancel", "/justbuild preview discard", "/justbuild undo cancel",
                "/justbuild help topics", "/justbuild profile Work"}) {
            assertEquals(command.substring(1), Chat.panelCommand(command).orElseThrow(), command);
            String alias = command.replaceFirst("/justbuild", "/jb");
            assertEquals(alias.substring(1), Chat.panelCommand(alias).orElseThrow(), alias);
        }
    }
}
