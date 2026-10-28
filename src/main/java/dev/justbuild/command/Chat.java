package dev.justbuild.command;

import dev.justbuild.config.JustBuildConfig;
import java.util.List;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** Chat messages, all prefixed with [JustBuild]. */
public final class Chat {
    /**
     * Server text in the panel may offer JustBuild actions, never unrelated commands under a spoofed label. Nor may it
     * start an AI request or answer one: the panel sends clicks like typed commands, so the player's game would take the
     * server's text as something the player asked its AI for. The panel's own answer buttons and Templates tab remain.
     */
    public static java.util.Optional<String> panelCommand(String command) {
        if (command == null || command.length() > 256 || command.chars().anyMatch(Character::isISOControl)) {
            return java.util.Optional.empty();
        }
        String clean = command.startsWith("/") ? command.substring(1) : command;
        int space = clean.indexOf(' ');
        String root = space < 0 ? clean : clean.substring(0, space);
        return JustBuildCommand.isRoot(root) && !dev.justbuild.relay.RelayGate.startsAi(clean)
                ? java.util.Optional.of(clean) : java.util.Optional.empty();
    }
    private Chat() {
    }

    public static Component info(String text) {
        return prefix().append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }

    public static Component progress(String text) {
        return prefix().append(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    /** A progress line with a [Cancel] button, for work that may take a while. */
    public static Component working(String text) {
        return prefix().append(Component.literal(text + " ").withStyle(ChatFormatting.GRAY))
                .append(button("Cancel", "/justbuild cancel", "Stop this request"));
    }

    /** A large plan waiting for the player: [Instant Build] confirms, [Discard] discards. */
    public static Component preview(String text) {
        return prefix().append(Component.literal(text + "\n").withStyle(ChatFormatting.YELLOW))
                .append(button("Instant Build", "/justbuild confirm", "Place this build"))
                .append(" ")
                .append(Component.literal("[Discard]").withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent.RunCommand("/justbuild preview discard"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                "Discard this plan (kept 30 minutes: Retry saved plan brings it back)")))));
    }

    /** Action-bar text (shown above the hotbar). */
    public static Component bar(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    /** {@code line} followed by a clickable [label] that runs {@code command}. */
    public static Component with(Component line, String label, String command, String hover) {
        return line.copy().append(" ").append(button(label, command, hover));
    }

    public static Component error(String text) {
        return prefix().append(Component.literal(text).withStyle(ChatFormatting.RED));
    }

    public static Component question(String question, List<String> options) {
        MutableComponent message = prefix().append(Component.literal(question).withStyle(ChatFormatting.AQUA));
        if (!options.isEmpty()) {
            message.append("\n");
            for (String option : options) {
                message.append(button(option, "/justbuild answer " + option, "Answer: " + option)).append(" ");
            }
        }
        return message.append(Component.literal("\nType your answer in chat, or click an option.")
                .withStyle(ChatFormatting.GRAY));
    }

    /** The undo preview with [Confirm undo] and [Keep it] buttons. */
    public static Component undoPrompt(String what, int changedSince) {
        String lost = "Anything you placed, broke, or changed at those spots since then is lost too"
                + (changedSince > 0 ? " (" + changedSince + " blocks differ from the build now)." : ".");
        return prefix().append(Component.literal(what + " ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(lost + "\n").withStyle(changedSince > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY))
                .append(Component.literal("[Confirm undo]").withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent.RunCommand("/justbuild undo confirm"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Run the undo")))))
                .append(" ")
                .append(button("Keep it", "/justbuild undo cancel", "Don't undo"));
    }

    /** {@code ai} ("AI 0:48, 23.4k tokens in, 1.9k out") may be null. */
    public static Component finished(String title, int blocks, String elapsed, String ai, String buildId) {
        return withActions(prefix().append(Component.literal("Finished “" + title + "” (" + blocks + " blocks, "
                + elapsed + " total" + (ai == null ? "" : "; " + ai) + "). ").withStyle(ChatFormatting.WHITE)), buildId);
    }

    public static Component updated(String title, int version, int changed, String elapsed, String ai, String buildId) {
        return withActions(prefix().append(Component.literal("Updated “" + title + "” to version " + version + " ("
                + changed + " blocks changed, " + elapsed + " total" + (ai == null ? "" : "; " + ai) + "). ")
                .withStyle(ChatFormatting.WHITE)), buildId);
    }

    /** A heads-up from the AI backend, such as a subscription limit warning. */
    public static Component notice(String text) {
        return prefix().append(Component.literal(text).withStyle(ChatFormatting.YELLOW));
    }

    /** The template list, grouped by category; names run the template, hover shows the request. */
    public static Component templates(List<dev.justbuild.prompt.TemplateLibrary.Template> templates) {
        MutableComponent message = prefix().append(Component.literal(templates.isEmpty()
                ? "No templates match." : "Templates (click one to build it):").withStyle(ChatFormatting.WHITE));
        String category = null;
        for (var t : templates) {
            if (!t.category().equals(category)) {
                category = t.category();
                message.append(Component.literal("\n" + category + ": ").withStyle(ChatFormatting.GRAY));
            }
            message.append(Component.literal("[" + t.name() + "]").withStyle(style -> style
                    .withColor(ChatFormatting.GREEN)
                    .withClickEvent(new ClickEvent.RunCommand("/justbuild template " + t.id()))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(t.request()))))).append(" ");
        }
        return message;
    }

    /** Every profile, the active one starred, each with [Use]. */
    public static Component profiles(List<JustBuildConfig.ModelProfile> profiles, String active,
            Function<JustBuildConfig.ModelProfile, String> label) {
        MutableComponent message = prefix().append(Component.literal("Profiles (edit them in the panel: Right Shift → Settings):")
                .withStyle(ChatFormatting.WHITE));
        for (JustBuildConfig.ModelProfile p : profiles) {
            boolean current = p.name.equals(active);
            message.append(Component.literal("\n" + (current ? "★ " : "  ") + p.name + " ").withStyle(current
                    ? ChatFormatting.GOLD : ChatFormatting.WHITE));
            message.append(Component.literal("(" + label.apply(p) + ") ").withStyle(ChatFormatting.GRAY));
            if (!current) {
                message.append(button("Use", "/justbuild profile " + p.name, "Switch to " + p.name));
            }
        }
        return message;
    }

    /** A model list; clicking a name sets it on the active profile. */
    public static Component models(String profile, List<String> models) {
        int shown = Math.min(models.size(), MAX_LISTED_MODELS);
        MutableComponent message = prefix().append(Component.literal(models.size() + (models.size() == 1 ? " model" : " models")
                + " for “" + profile + "” (click one to use it):\n").withStyle(ChatFormatting.WHITE));
        for (String model : models.subList(0, shown)) {
            message.append(Component.literal("[" + model + "]").withStyle(style -> style
                    .withColor(ChatFormatting.GREEN)
                    .withClickEvent(new ClickEvent.RunCommand("/justbuild model " + model))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Use " + model))))).append(" ");
        }
        if (shown < models.size()) {
            message.append(Component.literal("…and " + (models.size() - shown) + " more; the panel's model picker "
                    + "(Right Shift → Settings → Pick…) can filter them.").withStyle(ChatFormatting.GRAY));
        }
        return message;
    }

    private static final int MAX_LISTED_MODELS = 60;

    /** One history line: title, version count, age, and [Again] / [Edit]. */
    public static Component historyLine(String title, int versions, String age, String buildId) {
        return prefix().append(Component.literal("“" + title + "” · v" + versions + " · " + age + " ")
                        .withStyle(ChatFormatting.WHITE))
                .append(button("Again", "/justbuild again #" + buildId, "Build the original request again"))
                .append(" ")
                .append(Component.literal("[Edit]").withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.SuggestCommand("/justbuild edit #" + buildId + " "))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Describe a change to this build")))));
    }

    private static Component withActions(MutableComponent message, String buildId) {
        message.append(button("Undo", "/justbuild undo", "Undo the last change"))
                .append(" ")
                .append(Component.literal("[Edit]").withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.SuggestCommand("/justbuild edit #" + buildId + " "))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                "Describe a change, e.g. make the towers taller")))));
        return message;
    }

    static MutableComponent button(String label, String command, String hover) {
        return Component.literal("[" + label + "]").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static MutableComponent prefix() {
        return Component.literal("[JustBuild] ").withStyle(ChatFormatting.GOLD);
    }
}
