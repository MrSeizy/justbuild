package dev.justbuild.command;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CommandHelpTest {
    @BeforeAll static void bootstrap() { dev.justbuild.world.McBootstrap.init(); }

    @Test void everyRegisteredCommandAndLiteralBranchHasHelp() {
        assertEquals(JustBuildCommand.registeredSubcommands(), new TreeSet<>(CommandHelp.topics()));
        var entries = CommandHelp.entries("all");
        assertEquals(entries.size(), entries.stream().map(CommandHelp.Entry::syntax).distinct().count());
        assertTrue(entries.stream().allMatch(e -> !e.description().isBlank()));
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        JustBuildCommand.register(dispatcher);
        checkBranches(dispatcher.getRoot().getChild("justbuild"), List.of());
    }

    private static void checkBranches(CommandNode<CommandSourceStack> node, List<String> path) {
        if (node.getCommand() != null && !path.isEmpty()) {
            assertTrue(CommandHelp.entries(path.getFirst()).stream().anyMatch(e -> {
                var tokens = Arrays.asList(e.syntax().split("[\\s<>\\[\\]|]+"));
                int at = 0;
                for (String literal : path) {
                    while (at < tokens.size() && !tokens.get(at).equals(literal)) at++;
                    if (at == tokens.size()) return false;
                    at++;
                }
                return true;
            }), "Missing help for command branch: " + String.join(" ", path));
        }
        for (var child : node.getChildren()) {
            List<String> next = new ArrayList<>(path);
            if (child instanceof LiteralCommandNode<?>) next.add(child.getName());
            checkBranches(child, next);
        }
    }

    @Test void pagingShowsEveryCommandOnceAndOnlyHelpLinksRunImmediately() {
        List<String> shown = new ArrayList<>();
        int total = (CommandHelp.entries("all").size() + CommandHelp.PAGE_SIZE - 1) / CommandHelp.PAGE_SIZE;
        for (int page = 1; page <= total; page++) {
            Component rendered = CommandHelp.render(Integer.toString(page));
            List<String> rows = new ArrayList<>();
            rendered.visit((style, text) -> {
                if (style.getClickEvent() instanceof ClickEvent.SuggestCommand suggest) {
                    rows.add(text.substring("/justbuild ".length()));
                    assertTrue(suggest.command().startsWith("/justbuild "));
                    assertFalse(suggest.command().contains("<"));
                    assertFalse(suggest.command().contains("["));
                } else if (style.getClickEvent() instanceof ClickEvent.RunCommand run) {
                    assertTrue(run.command().equals("/justbuild help") || run.command().startsWith("/justbuild help "),
                            "A help link would execute an action: " + run.command());
                }
                return Optional.<Void>empty();
            }, Style.EMPTY);
            assertTrue(!rows.isEmpty() && rows.size() <= CommandHelp.PAGE_SIZE);
            assertEquals(page > 1, rendered.getString().contains("[Previous]"));
            assertEquals(page < total, rendered.getString().contains("[Next]"));
            shown.addAll(rows);
        }
        assertEquals(CommandHelp.entries("all").stream().map(CommandHelp.Entry::syntax).toList(), shown);
    }

    @Test void topicsAndPageSuggestionsKeepNavigationWithinTheTopic() {
        String zone = CommandHelp.render("  ZONE 2  ").getString();
        assertTrue(zone.contains("Commands · zone · page 2/"));
        assertTrue(zone.contains("/justbuild zone expand"));
        assertFalse(zone.contains("/justbuild box"));
        assertTrue(CommandHelp.suggestions("zone ").contains("zone 2"));
        assertTrue(CommandHelp.suggestions("").containsAll(List.of("zone", "new", "topics", "1")));
        assertTrue(CommandHelp.render("topics").getString().contains("[new]"));
        assertTrue(CommandHelp.render("new").getString().contains("Leave editing mode"));
        assertEquals(CommandHelp.render("1").getString(), CommandHelp.render("").getString());
    }

    @Test void invalidTopicsAndPagesFailClearly() {
        for (String query : List.of("0", "99999", "99999999999999999999999", "no-such-command", "zone 0", "zone nope", "zone 1 extra"))
            assertThrows(IllegalArgumentException.class, () -> CommandHelp.render(query), query);
    }
}
