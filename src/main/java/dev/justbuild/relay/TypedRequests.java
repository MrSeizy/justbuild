package dev.justbuild.relay;

import dev.justbuild.command.JustBuildCommand;
import dev.justbuild.prompt.AlternativeRequests;
import dev.justbuild.prompt.InteriorRequests;
import dev.justbuild.prompt.TemplateLibrary;
import dev.justbuild.session.EditTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What an outgoing /justbuild command asks the AI for, for {@link PromptCheck}. Only the subcommands that start an AI
 * request count. Every other subcommand (select, rename, zone, again, ...) counts for nothing, so text a server put
 * into a panel or chat button never becomes something the player "typed".
 */
public final class TypedRequests {
    /** Requests the command starts, and the answer it gives (or null). */
    public record Typed(List<String> requests, String answer) {
        static final Typed NONE = new Typed(List.of(), null);
    }

    private TypedRequests() {
    }

    public static Typed of(String command) {
        String text = command.strip();
        String[] words = text.split("\\s+", 3);
        if (words.length < 2 || !JustBuildCommand.isRoot(words[0].toLowerCase(Locale.ROOT))) {
            return Typed.NONE;
        }
        String sub = words[1].toLowerCase(Locale.ROOT);
        String after = words.length > 2 ? words[2].strip() : "";
        List<String> requests = new ArrayList<>();
        switch (sub) {
            case "build", "connect" -> requests.add(after);
            case "edit" -> requests.add(EditTarget.parse(after).change());
            case "answer" -> {
                return after.isBlank() ? Typed.NONE : new Typed(List.of(), after);
            }
            case "interior" -> {
                try {
                    var c = InteriorRequests.parse(after);
                    requests.add(InteriorRequests.request(c.purpose(), c.stage(), c.detail()));
                } catch (IllegalArgumentException ignored) { }
            }
            case "alternatives" -> {
                try {
                    var choice = AlternativeRequests.parse(after);
                    String base = choice.edit() ? EditTarget.parse(choice.text()).change() : choice.text();
                    for (int i = 1; i <= choice.count(); i++) requests.add(AlternativeRequests.variant(base, i, choice.count()));
                } catch (IllegalArgumentException ignored) { }
            }
            case "template" -> TemplateLibrary.defaults().all().stream()
                    .filter(t -> t.id().equalsIgnoreCase(after)).findFirst().ifPresent(t -> requests.add(t.request()));
            default -> {
                // "/justbuild <description>": any word that isn't a subcommand starts a description.
                if (!JustBuildCommand.subcommands().contains(sub)) requests.add(text.substring(words[0].length()).strip());
            }
        }
        requests.removeIf(String::isBlank);
        return new Typed(List.copyOf(requests), null);
    }
}
