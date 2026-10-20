package dev.justbuild.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** The complete chat reference, paged and filterable by command, without starting any AI work. */
final class CommandHelp {
    static final int PAGE_SIZE = 5;
    record Entry(String syntax, String description) {
        String topic() { return syntax.startsWith("<") ? "build" : syntax.split(" ", 2)[0]; }
        String suggestion() {
            int end = syntax.length();
            for (char marker : new char[] {'<', '['}) {
                int at = syntax.indexOf(marker);
                if (at >= 0) end = Math.min(end, at);
            }
            return "/justbuild " + syntax.substring(0, end);
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            e("new", "Leave editing mode, ready to draw a zone for a new build."),
            e("build <description>", "Create a new build in your zone, or where you aim."),
            e("edit [#id] <change>", "Change the selected build, or the build with that id."),
            e("confirm", "Place the waiting preview."),
            e("help [topic|page] [page]", "Browse all commands, or a topic such as zone or box."),
            e("<description>", "Shorthand for build; use build when starting with a command word."),
            e("answer <text>", "Answer the AI's question; ordinary chat works too."),
            e("cancel", "Stop the current request, preview, or placement."),
            e("autobuild [on|off]", "Toggle automatic placement, or explicitly turn it on/off."),
            e("retry", "Preview the retained plan again without another AI call."),
            e("zone", "Show your current build zone."),
            e("zone wand", "Equip the gold Zone wand to draw or resize a zone."),
            e("zone 1 [x y z]", "Set the first ground corner; omit coordinates to use your aim."),
            e("zone 2 [x y z]", "Set the opposite corner and finish the zone."),
            e("zone clear", "Remove the current build zone."),
            e("zone expand [blocks]", "Grow each edge by 1–127 blocks; default 4."),
            e("zone include", "Grow the zone to include the block you aim at or stand on."),
            e("zone adjust move <dx> <dy> <dz>", "Move the zone by up to 16 blocks per axis."),
            e("zone adjust height <minY> <maxY>", "Set inclusive bottom and top height limits."),
            e("zone adjust height auto", "Remove the zone's height limits."),
            e("zone adjust corner <index> <x> <z>", "Move a zone corner or polygon vertex; indices start at 0."),
            e("zone adjust polygon start", "Start drawing a polygon with the wand."),
            e("zone adjust polygon add", "Add the block under your crosshair as the next vertex."),
            e("zone adjust polygon undo", "Remove the most recently added vertex."),
            e("zone adjust polygon finish", "Finish the polygon and use it as your zone."),
            e("zone adjust polygon cancel", "Cancel the polygon and restore the previous zone."),
            e("select", "Select the build you aim at, even outside its saved box."),
            e("select #<id>", "Select a saved build by id and display its saved zone."),
            e("select clear", "Leave editing mode; the same action as new."),
            e("rename <name>", "Rename the selected build; up to 60 characters."),
            e("box", "Show the current box."),
            e("box wand", "Equip the Builder Wand in purple Box mode to select or resize a box."),
            e("box 1 [x y z]", "Start a box at your target or the given coordinates."),
            e("box 2 [x y z]", "Grow the box to include another target or coordinate."),
            e("box clear", "Clear the box so edits can use the whole build area."),
            e("box roof", "Suggest the upper section of the selected build."),
            e("box wall", "Suggest the selected build's wall facing you."),
            e("box room", "Suggest an enclosed room and its surrounding shell."),
            e("box save <name>", "Save the box as a named part of the selected build."),
            e("box select <name>", "Restore a named part as the box."),
            e("box delete <name>", "Delete a saved part name; no blocks are removed."),
            e("box adjust move <dx> <dy> <dz>", "Move the box by up to 16 blocks per axis."),
            e("box adjust min <x> <y> <z>", "Set the box's minimum corner to absolute coordinates."),
            e("box adjust max <x> <y> <z>", "Set the box's maximum corner to absolute coordinates."),
            e("box adjust corner <index> <x> <y> <z>", "Move one of eight corners, indexed 0–7; keep its opposite."),
            e("preview discard", "Discard the preview but retain its plan for retry."),
            e("preview move <x> <y> <z>", "Move a new-build/copy preview by up to 16 blocks per axis."),
            e("preview rotate <degrees>", "Rotate a new-build/copy preview by 90, 180, or 270 degrees."),
            e("preview snap", "Snap a new-build/copy preview to the ground."),
            e("preview option <number>", "Show a completed alternative, numbered 1–3."),
            e("alternatives build <2|3> <description>", "Generate separate new-build previews to choose from."),
            e("alternatives edit <2|3> <change>", "Generate separate edit previews for the selected build."),
            e("history", "List your recent builds, with edit and rebuild actions."),
            e("history preview <version-id>", "Preview restoring a saved version; confirm to apply it."),
            e("again #<id>", "Make a new build from that build's original request."),
            e("undo", "Preview undoing your last build or edit."),
            e("undo confirm", "Apply the pending undo."),
            e("undo cancel", "Dismiss the pending undo and keep the current blocks."),
            e("checkpoint", "List the selected build's named checkpoints."),
            e("checkpoint save <name>", "Save the current plan as a named checkpoint."),
            e("checkpoint restore <name>", "Preview restoring a checkpoint as an undoable edit."),
            e("add <name>", "Register the selected part as a build without changing blocks."),
            e("inspect", "Inspect loaded blocks around your zone or target; no AI call."),
            e("materials", "Refresh the selected building's block palette."),
            e("materials reroll [seed]", "Preview a deterministic texture reroll; no AI call."),
            e("materials weight <block> <weight>", "Preview a relative mix weight from 0 to 100; no AI call."),
            e("materials sample", "Sample the material under your crosshair."),
            e("materials replace <from> <to>", "Preview a block-type swap, keeping compatible properties."),
            e("repair", "Preview repairing missing blocks from the current version."),
            e("repair missing [checkpoint]", "Preview filling missing blocks, optionally from a checkpoint."),
            e("repair all [checkpoint]", "Preview restoring all changed blocks, optionally from a checkpoint."),
            e("interior <purpose> <stage> [details]", "Purpose: home/workshop/tavern/custom; stage: complete/layout/furnish."),
            e("quality repair <issue-id>", "Preview a suggested repair from the Checks list."),
            e("quality revert", "Revert the current suggested quality repair."),
            e("walkability", "Check routes and show markers for the preview or selected build."),
            e("walkability clear", "Hide the route markers."),
            e("constraints height <number>", "Set a maximum build height, 0–256; 0 removes the limit."),
            e("constraints floors <number>", "Request 0–64 floors; 0 removes the requirement."),
            e("constraints materials <ids|any>", "Allow comma-separated block ids, or any material."),
            e("constraints clear", "Clear height, floor, and material requirements."),
            e("lock", "List your protected areas and their ids."),
            e("lock <name>", "Protect the selected part from AI changes."),
            e("unlock <id>", "Remove a protected area you may manage."),
            e("link a [id]", "Choose connection endpoint A; defaults to the selected build."),
            e("link b [id]", "Choose connection endpoint B; defaults to the selected build."),
            e("link clear", "Clear both connection endpoints."),
            e("connect <description>", "Plan a path, bridge, or other connection between A and B."),
            e("design", "List your reusable designs; same as design list."),
            e("design list", "List saved designs and their ids."),
            e("design save <name>", "Capture the selected build as a reusable design."),
            e("design place <id> [rotation] [from=to,...]", "Preview a copy here, with optional rotation and material swaps."),
            e("design folder <id> [name]", "Move a design to a folder; omit the name for Unfiled."),
            e("design export <id> [format]", "Export as justbuild.json (default), litematic, or schem."),
            e("design import <file> [air]", "Import a library file; air also retains empty schematic cells."),
            e("design refresh", "Refresh the shared library's file list."),
            e("project", "List your projects; same as project list."),
            e("project list", "List projects you own or collaborate on."),
            e("project create <name>", "Create and select a project for related buildings."),
            e("project select <id>", "Select a project by id."),
            e("project clear", "Leave project mode without deleting the project."),
            e("project add", "Add the selected build to the selected project."),
            e("project remove", "Remove the selected build from the selected project."),
            e("project style <style>", "Set the project's shared architectural style."),
            e("project palette <ids|building|clear>", "Use up to 12 block ids, the current build's palette, or none."),
            e("project invite <player>", "Let a player edit your project's buildings. Use their UUID if they haven't joined in the last month."),
            e("project uninvite <player>", "Remove a player's permission to edit your project."),
            e("project leave [id]", "Leave a project you collaborate on; defaults to the active one."),
            e("release #<id>", "Release another player's waiting change on a build you manage."),
            e("templates [filter]", "List ready-made build requests, optionally filtered by name."),
            e("template <id>", "Start a new build using a ready-made request."),
            e("detail <quick|detailed|masterpiece>", "Choose how much planning and review the AI performs."),
            e("style <name>", "Set the building style; Tab lists the available styles."),
            e("profile [name]", "List AI profiles; add a name to switch to it."),
            e("model [name|default]", "List models; add a name to choose one, or default to reset."),
            e("profiles", "Alias for /justbuild profile (list profiles)."),
            e("models", "Alias for /justbuild model (list models)."),
            e("provider <claude|codex|grok|openai>", "Switch to a profile using that backend."),
            e("playerai [require|prefer|off]", "Show or change the server's player AI policy; require is the default."),
            e("usage", "Refresh session AI usage in the panel."),
            e("usage show", "Refresh session AI usage in the panel."),
            e("usage <max-calls> <max-reported-tokens>", "Limit server-handled requests this session; 0 is unlimited."),
            e("usage reset", "Reset idle session usage counters."),
            e("status", "Show the active AI setup and build/request status."),
            e("doctor", "Check AI profiles for install, login, or endpoint problems."),
            e("reload", "Reload the server's JustBuild configuration.")
    );

    private CommandHelp() { }
    private static Entry e(String syntax, String description) { return new Entry(syntax, description); }
    static List<String> topics() { return ENTRIES.stream().map(Entry::topic).distinct().sorted().toList(); }
    static List<Entry> entries(String topic) {
        if (topic.equals("all")) return ENTRIES;
        List<Entry> selected = ENTRIES.stream().filter(e -> e.topic().equals(topic)).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("Unknown help topic. Use /justbuild help topics to browse.");
        return selected;
    }
    private static int pages(List<Entry> entries) { return (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE; }

    static int show(CommandSourceStack source, String query) {
        try {
            source.sendSystemMessage(render(query));
            return 1;
        } catch (IllegalArgumentException e) {
            source.sendSystemMessage(Chat.with(Chat.error(e.getMessage()), "Help", "/justbuild help", "Show command help"));
            return 0;
        }
    }

    static Component render(String query) {
        String clean = query.strip().toLowerCase(Locale.ROOT);
        if (clean.equals("topics")) return topicIndex();
        String[] words = clean.isEmpty() ? new String[0] : clean.split("\\s+");
        String topic = "all";
        int page = 1;
        try {
            if (words.length == 1 && words[0].matches("[0-9]+")) page = Integer.parseInt(words[0]);
            else if (words.length <= 2) {
                if (words.length > 0) topic = words[0];
                if (words.length == 2) page = Integer.parseInt(words[1]);
            } else throw new IllegalArgumentException("Use /justbuild help [topic] [page].");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Use a page number, for example /justbuild help zone 2.");
        }
        List<Entry> selected = entries(topic);
        int total = pages(selected);
        if (page < 1 || page > total) throw new IllegalArgumentException("Help for " + topic + " has pages 1–" + total + ".");
        MutableComponent message = Chat.info("Commands · " + topic + " · page " + page + "/" + total).copy();
        message.append(Component.literal("\n<required> [optional] · click a command to fill chat").withStyle(ChatFormatting.GRAY));
        int start = (page - 1) * PAGE_SIZE;
        for (Entry entry : selected.subList(start, Math.min(start + PAGE_SIZE, selected.size()))) {
            message.append("\n").append(Component.literal("/justbuild " + entry.syntax()).withStyle(style -> style
                    .withColor(ChatFormatting.YELLOW)
                    .withClickEvent(new ClickEvent.SuggestCommand(entry.suggestion()))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(entry.description() + "\nClick to fill chat; edit before sending.")))))
                    .append(Component.literal(" — " + entry.description()).withStyle(ChatFormatting.WHITE));
        }
        message.append("\n").append(Chat.button("Topics", "/justbuild help topics", "Browse commands by topic"));
        if (!topic.equals("all")) message.append(" ").append(Chat.button("All", "/justbuild help", "Show all commands"));
        if (page > 1) message.append(" ").append(Chat.button("Previous", pageCommand(topic, page - 1), "Previous page"));
        if (page < total) message.append(" ").append(Chat.button("Next", pageCommand(topic, page + 1), "Next page"));
        return message;
    }

    private static String pageCommand(String topic, int page) {
        return "/justbuild help " + (topic.equals("all") ? "" : topic + " ") + page;
    }

    private static Component topicIndex() {
        MutableComponent message = Chat.info("Help topics (click one):").copy();
        List<String> topics = topics();
        for (int i = 0; i < topics.size(); i++) {
            message.append(i % 5 == 0 ? "\n" : " ")
                    .append(Chat.button(topics.get(i), "/justbuild help " + topics.get(i), "Show " + topics.get(i) + " commands"));
        }
        return message.append("\n").append(Chat.button("All commands", "/justbuild help", "Browse every command"));
    }

    static List<String> suggestions(String remaining) {
        String clean = remaining.stripLeading().toLowerCase(Locale.ROOT);
        if (clean.contains(" ")) {
            String topic = clean.split("\\s+", 2)[0];
            if (!topic.equals("all") && !topics().contains(topic)) return List.of();
            return IntStream.rangeClosed(1, pages(entries(topic))).mapToObj(page -> topic + " " + page).toList();
        }
        List<String> options = new ArrayList<>(topics());
        options.add("all");
        options.add("topics");
        IntStream.rangeClosed(1, pages(ENTRIES)).mapToObj(Integer::toString).forEach(options::add);
        return List.copyOf(options);
    }
}
