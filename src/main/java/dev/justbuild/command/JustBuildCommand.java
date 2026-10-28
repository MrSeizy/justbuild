package dev.justbuild.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.justbuild.JustBuildMod;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.prompt.Guidance;
import dev.justbuild.prompt.TemplateLibrary;
import dev.justbuild.session.BuildSessionManager;
import dev.justbuild.world.ZoneWand;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** /justbuild (or /jb) and its subcommands. Literal subcommands win over the free-text description. */
public final class JustBuildCommand {
    private JustBuildCommand() {
    }

    /** The literal subcommands of /justbuild (JustBuildCommandTest checks this against the tree). */
    private static final java.util.Set<String> SUBCOMMANDS = java.util.Set.of(
            "add", "again", "alternatives", "answer", "autobuild", "box", "build", "cancel", "checkpoint", "confirm", "connect",
            "constraints", "construction", "design", "detail", "doctor", "edit", "help", "history", "inspect", "interior", "link", "lock", "materials",
            "model", "models", "new", "playerai", "preview", "profile", "profiles", "project", "provider", "reload", "rename",
            "quality", "release", "repair", "retry", "select", "status", "style", "template", "templates", "undo", "unlock",
            "usage", "walkability", "zone");

    public static java.util.Set<String> subcommands() {
        return SUBCOMMANDS;
    }

    /** Shared by command dispatch, typed-request tracking, and panel-action filtering. */
    public static boolean isRoot(String name) {
        return name.equals("justbuild") || name.equals("jb");
    }

    /** The literal subcommands as registered, for tests. */
    public static java.util.Set<String> registeredSubcommands() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        register(dispatcher);
        return dispatcher.getRoot().getChild("justbuild").getChildren().stream()
                .filter(n -> n instanceof com.mojang.brigadier.tree.LiteralCommandNode<?>)
                .map(com.mojang.brigadier.tree.CommandNode::getName)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = dispatcher.register(Commands.literal("justbuild")
                .requires(JustBuildCommand::allowed)
                .executes(ctx -> CommandHelp.show(ctx.getSource(), ""))
                .then(Commands.literal("help").executes(ctx -> CommandHelp.show(ctx.getSource(), ""))
                        .then(Commands.argument("topic_or_page", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        CommandHelp.suggestions(builder.getRemaining()), builder))
                                .executes(ctx -> CommandHelp.show(ctx.getSource(), StringArgumentType.getString(ctx, "topic_or_page")))))
                .then(Commands.literal("new").executes(ctx -> run(ctx, BuildSessionManager::deselectBuild)))
                .then(Commands.literal("construction").executes(ctx -> run(ctx, (m, s) -> m.constructionCommand(s, "status")))
                        .then(Commands.argument("action", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("start guide", "start assist", "start cobble",
                                        "guide", "assist", "cobble", "pause", "resume", "layer", "chest", "unlink", "finish"), builder))
                                .executes(ctx -> run(ctx, (m, s) -> m.constructionCommand(s, StringArgumentType.getString(ctx, "action"))))))
                .then(Commands.literal("build").then(Commands.argument("description", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx, (m, s) -> m.start(s, StringArgumentType.getString(ctx, "description"))))))
                .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx, (m, s) -> m.addExisting(s, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("interior").then(Commands.argument("choices", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx, (m, s) -> m.interior(s, StringArgumentType.getString(ctx, "choices"))))))
                .then(Commands.literal("project").executes(ctx -> run(ctx, (m, s) -> m.project(s, "list")))
                        .then(Commands.argument("action", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.project(s, StringArgumentType.getString(ctx, "action"))))))
                .then(Commands.literal("walkability").executes(ctx -> run(ctx, (m, s) -> m.walkability(s, false)))
                        .then(Commands.literal("clear").executes(ctx -> run(ctx, (m, s) -> m.walkability(s, true)))))
                .then(Commands.literal("repair").executes(ctx -> run(ctx, (m, s) -> m.repair(s, true, null)))
                        .then(repairMode("missing", true)).then(repairMode("all", false)))
                .then(Commands.literal("materials").executes(ctx -> run(ctx, BuildSessionManager::materials))
                        .then(Commands.literal("reroll").executes(ctx -> run(ctx, (m, s) -> m.texture(s, null, null, null)))
                                .then(Commands.argument("seed", IntegerArgumentType.integer()).executes(ctx -> run(ctx,
                                        (m, s) -> m.texture(s, IntegerArgumentType.getInteger(ctx, "seed"), null, null)))))
                        .then(Commands.literal("weight").then(Commands.argument("block", net.minecraft.commands.arguments.IdentifierArgument.id())
                                .then(Commands.argument("weight", IntegerArgumentType.integer(0, 100)).executes(ctx -> run(ctx,
                                        (m, s) -> m.texture(s, null, net.minecraft.commands.arguments.IdentifierArgument.getId(ctx, "block").toString(),
                                                IntegerArgumentType.getInteger(ctx, "weight")))))))
                        .then(Commands.literal("sample").executes(ctx -> run(ctx, BuildSessionManager::sampleMaterial)))
                        .then(Commands.literal("replace").then(Commands.argument("from", net.minecraft.commands.arguments.IdentifierArgument.id())
                                .then(Commands.argument("to", net.minecraft.commands.arguments.IdentifierArgument.id()).executes(ctx -> run(ctx,
                                        (m, s) -> m.replaceMaterial(s, net.minecraft.commands.arguments.IdentifierArgument.getId(ctx, "from").toString(), net.minecraft.commands.arguments.IdentifierArgument.getId(ctx, "to").toString())))))))
                .then(Commands.literal("inspect").executes(ctx -> run(ctx, BuildSessionManager::inspect)))
                .then(Commands.literal("retry").executes(ctx -> run(ctx, BuildSessionManager::retry)))
                .then(Commands.literal("alternatives").then(alternatives("build", false)).then(alternatives("edit", true)))
                .then(Commands.literal("preview")
                        .then(Commands.literal("discard").executes(ctx -> run(ctx, BuildSessionManager::discardPreview)))
                        .then(Commands.literal("move").then(Commands.argument("x", IntegerArgumentType.integer(-16, 16))
                                .then(Commands.argument("y", IntegerArgumentType.integer(-16, 16))
                                        .then(Commands.argument("z", IntegerArgumentType.integer(-16, 16))
                                                .executes(ctx -> run(ctx, (m, s) -> m.adjustPreview(s,
                                                        IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"),
                                                        IntegerArgumentType.getInteger(ctx, "z"), 0, false)))))))
                        .then(Commands.literal("rotate").then(Commands.argument("degrees", IntegerArgumentType.integer(0, 270))
                                .executes(ctx -> run(ctx, (m, s) -> m.adjustPreview(s, 0, 0, 0,
                                        IntegerArgumentType.getInteger(ctx, "degrees"), false)))))
                        .then(Commands.literal("snap").executes(ctx -> run(ctx, (m, s) -> m.adjustPreview(s, 0, 0, 0, 0, true))))
                        .then(Commands.literal("option").then(Commands.argument("number", IntegerArgumentType.integer(1, 3))
                                .executes(ctx -> run(ctx, (m, s) -> m.chooseAlternative(s, IntegerArgumentType.getInteger(ctx, "number")))))))
                .then(Commands.literal("quality").then(Commands.argument("action", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx, (m, s) -> m.qualityRepair(s, StringArgumentType.getString(ctx, "action"))))))
                .then(Commands.literal("usage").executes(ctx -> run(ctx, (m, s) -> m.usage(s, "show")))
                        .then(Commands.argument("values", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.usage(s, StringArgumentType.getString(ctx, "values"))))))
                .then(Commands.literal("constraints").then(Commands.argument("values", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx, (m, s) -> m.constraints(s, StringArgumentType.getString(ctx, "values"))))))
                .then(Commands.literal("box").executes(ctx -> run(ctx, BuildSessionManager::partInfo))
                        .then(Commands.literal("wand").executes(ctx -> run(ctx, (m, s) -> m.partWand(s, true))))
                        .then(partCorner(1)).then(partCorner(2))
                        .then(namedPart("save")).then(namedPart("select")).then(namedPart("delete"))
                        .then(Commands.literal("adjust").then(Commands.argument("values", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.adjustPart(s, StringArgumentType.getString(ctx, "values"))))))
                        .then(Commands.literal("roof").executes(ctx -> run(ctx, (m, s) -> m.selectPart(s, "roof"))))
                        .then(Commands.literal("wall").executes(ctx -> run(ctx, (m, s) -> m.selectPart(s, "wall"))))
                        .then(Commands.literal("room").executes(ctx -> run(ctx, (m, s) -> m.selectPart(s, "room"))))
                        .then(Commands.literal("clear").executes(ctx -> run(ctx, BuildSessionManager::clearPart))))
                .then(Commands.literal("lock").executes(ctx -> run(ctx, BuildSessionManager::lockedInfo))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.lockPart(s, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("unlock").then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx, (m, s) -> m.unlockPart(s, StringArgumentType.getString(ctx, "id"))))))
                .then(Commands.literal("link").then(link("a", 0)).then(link("b", 1))
                        .then(Commands.literal("clear").executes(ctx -> run(ctx, (m, s) -> m.linkBuild(s, -1, null)))))
                .then(Commands.literal("connect").then(Commands.argument("description", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx, (m, s) -> m.connectBuilds(s, StringArgumentType.getString(ctx, "description"))))))
                .then(Commands.literal("select").executes(ctx -> run(ctx, BuildSessionManager::selectBuildAt))
                        .then(Commands.literal("clear").executes(ctx -> run(ctx, BuildSessionManager::deselectBuild)))
                        .then(Commands.argument("id", StringArgumentType.greedyString()).executes(ctx -> run(ctx,
                                (m, s) -> m.selectBuild(s, StringArgumentType.getString(ctx, "id"))))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> run(ctx,
                                (m, s) -> m.renameBuild(s, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("checkpoint").executes(ctx -> run(ctx, BuildSessionManager::checkpoints))
                        .then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.saveCheckpoint(s, StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("restore").then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.restoreCheckpoint(s, StringArgumentType.getString(ctx, "name")))))))
                .then(Commands.literal("design").executes(ctx -> run(ctx, BuildSessionManager::designs))
                        .then(Commands.literal("list").executes(ctx -> run(ctx, BuildSessionManager::designs)))
                        .then(Commands.literal("refresh").executes(ctx -> run(ctx, BuildSessionManager::refreshLibrary)))
                        .then(Commands.literal("export").then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> run(ctx, (m, s) -> m.exportDesign(s, StringArgumentType.getString(ctx, "id"))))
                                .then(Commands.argument("format", StringArgumentType.word()).suggests((ctx, builder) ->
                                        SharedSuggestionProvider.suggest(List.of("justbuild.json", "litematic", "schem"), builder))
                                        .executes(ctx -> run(ctx, (m, s) -> m.exportDesign(s, StringArgumentType.getString(ctx, "id"),
                                                StringArgumentType.getString(ctx, "format")))))))
                        .then(Commands.literal("import").then(Commands.argument("file", StringArgumentType.word())
                                .executes(ctx -> run(ctx, (m, s) -> m.importDesign(s, StringArgumentType.getString(ctx, "file"))))
                                .then(Commands.literal("air").executes(ctx -> run(ctx, (m, s) -> m.importDesign(s,
                                        StringArgumentType.getString(ctx, "file"), true))))))
                        .then(Commands.literal("folder").then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> run(ctx, (m, s) -> m.designFolder(s, StringArgumentType.getString(ctx, "id"), "")))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> run(ctx, (m, s) -> m.designFolder(s, StringArgumentType.getString(ctx, "id"),
                                                StringArgumentType.getString(ctx, "name")))))))
                        .then(Commands.literal("save").then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.saveDesign(s, StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("place").then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> run(ctx, (m, s) -> m.placeDesign(s, StringArgumentType.getString(ctx, "id"), 0, "")))
                                .then(Commands.argument("rotation", IntegerArgumentType.integer(0, 270))
                                        .executes(ctx -> run(ctx, (m, s) -> m.placeDesign(s, StringArgumentType.getString(ctx, "id"),
                                                IntegerArgumentType.getInteger(ctx, "rotation"), "")))
                                        .then(Commands.argument("substitutions", StringArgumentType.greedyString())
                                                .executes(ctx -> run(ctx, (m, s) -> m.placeDesign(s, StringArgumentType.getString(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "rotation"),
                                                        StringArgumentType.getString(ctx, "substitutions")))))))))
                .then(Commands.literal("zone").executes(ctx -> run(ctx, BuildSessionManager::zoneInfo))
                        .then(Commands.literal("wand").executes(ctx -> run(ctx, (m, s) -> m.partWand(s, false))))
                        .then(Commands.literal("adjust").then(Commands.argument("values", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.adjustZone(s, StringArgumentType.getString(ctx, "values"))))))
                        .then(zoneCorner(1))
                        .then(zoneCorner(2))
                        .then(Commands.literal("expand").executes(ctx -> run(ctx, (m, s) -> m.expandZone(s, 4)))
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 127))
                                        .executes(ctx -> run(ctx, (m, s) -> m.expandZone(s,
                                                IntegerArgumentType.getInteger(ctx, "blocks"))))))
                        .then(Commands.literal("include").executes(ctx -> run(ctx, BuildSessionManager::includeZoneTarget)))
                        .then(Commands.literal("clear").executes(ctx -> run(ctx, BuildSessionManager::clearZone))))
                .then(Commands.literal("answer")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.answer(s, StringArgumentType.getString(ctx, "text"))))))
                .then(Commands.literal("edit")
                        .then(Commands.argument("change", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.edit(s, StringArgumentType.getString(ctx, "change"))))))
                .then(Commands.literal("templates").executes(ctx -> run(ctx, (m, s) -> m.templates(s, "")))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.templates(s, StringArgumentType.getString(ctx, "filter"))))))
                .then(Commands.literal("template")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        TemplateLibrary.defaults().all().stream().map(TemplateLibrary.Template::id).toList(), builder))
                                .executes(ctx -> run(ctx, (m, s) -> m.template(s, StringArgumentType.getString(ctx, "id"))))))
                .then(Commands.literal("history").executes(ctx -> run(ctx, BuildSessionManager::history))
                        .then(Commands.literal("preview").then(Commands.argument("version", StringArgumentType.word())
                                .executes(ctx -> run(ctx, (m, s) -> m.previewVersion(s, StringArgumentType.getString(ctx, "version")))))))
                .then(Commands.literal("autobuild").executes(ctx -> run(ctx, (m, s) -> m.autoBuild(s, "")))
                        .then(Commands.argument("state", StringArgumentType.word())
                                .executes(ctx -> run(ctx, (m, s) -> m.autoBuild(s, StringArgumentType.getString(ctx, "state"))))))
                .then(Commands.literal("release")
                        .then(Commands.argument("build", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.release(s, StringArgumentType.getString(ctx, "build"))))))
                .then(Commands.literal("again")
                        .then(Commands.argument("build", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.again(s, StringArgumentType.getString(ctx, "build"))))))
                .then(Commands.literal("undo").executes(ctx -> run(ctx, BuildSessionManager::undo))
                        .then(Commands.literal("confirm").executes(ctx -> run(ctx, BuildSessionManager::confirmUndo)))
                        .then(Commands.literal("cancel").executes(ctx -> run(ctx, BuildSessionManager::cancelUndo))))
                .then(Commands.literal("cancel").executes(ctx -> run(ctx, BuildSessionManager::cancel)))
                .then(Commands.literal("confirm").executes(ctx -> run(ctx, BuildSessionManager::confirm)))
                .then(Commands.literal("playerai")
                        .executes(ctx -> run(ctx, BuildSessionManager::playerAi))
                        .then(Commands.argument("policy", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(JustBuildConfig.PLAYER_AI, builder))
                                .executes(ctx -> run(ctx, (m, s) -> m.setPlayerAi(s, StringArgumentType.getString(ctx, "policy"))))))
                .then(Commands.literal("provider")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(JustBuildConfig.PROVIDERS, builder))
                                .executes(ctx -> run(ctx, (m, s) -> m.setProvider(s, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("profile")
                        .executes(ctx -> run(ctx, BuildSessionManager::profiles))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    BuildSessionManager m = JustBuildMod.manager();
                                    return SharedSuggestionProvider.suggest(m == null ? List.of() : m.profileNames(), builder);
                                })
                                .executes(ctx -> run(ctx, (m, s) -> m.setProfile(s, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("profiles").executes(ctx -> run(ctx, BuildSessionManager::profiles)))
                .then(Commands.literal("models").executes(ctx -> run(ctx, BuildSessionManager::models)))
                .then(Commands.literal("model")
                        .executes(ctx -> run(ctx, BuildSessionManager::models))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, (m, s) -> m.setModel(s, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("detail")
                        .then(Commands.argument("level", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        java.util.Arrays.stream(Guidance.DetailLevel.values()).map(Guidance.DetailLevel::id).toList(), builder))
                                .executes(ctx -> run(ctx, (m, s) -> m.setDetail(s, StringArgumentType.getString(ctx, "level"))))))
                .then(Commands.literal("style")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        java.util.Arrays.stream(Guidance.Style.values()).map(Guidance.Style::id).toList(), builder))
                                .executes(ctx -> run(ctx, (m, s) -> m.setStyle(s, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("status").executes(ctx -> run(ctx, BuildSessionManager::status)))
                .then(Commands.literal("doctor").executes(ctx -> run(ctx, BuildSessionManager::doctor)))
                .then(Commands.literal("reload").executes(ctx -> run(ctx, BuildSessionManager::reload)))
                .then(Commands.argument("description", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx, (m, s) -> m.start(s, StringArgumentType.getString(ctx, "description"))))));
        dispatcher.register(Commands.literal("jb").requires(root.getRequirement()).executes(root.getCommand()).redirect(root));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> namedPart(String action) {
        return Commands.literal(action).then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> run(ctx, (m, s) -> m.namedPart(s, action, StringArgumentType.getString(ctx, "name")))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> repairMode(String name, boolean missing) {
        return Commands.literal(name).executes(ctx -> run(ctx, (m, s) -> m.repair(s, missing, null)))
                .then(Commands.argument("checkpoint", StringArgumentType.greedyString())
                        .executes(ctx -> run(ctx, (m, s) -> m.repair(s, missing, StringArgumentType.getString(ctx, "checkpoint")))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> alternatives(String name, boolean edit) {
        return Commands.literal(name).then(Commands.argument("count", IntegerArgumentType.integer(2, 3))
                .then(Commands.argument("request", StringArgumentType.greedyString()).executes(ctx -> run(ctx,
                        (m, s) -> m.startAlternatives(s, edit, IntegerArgumentType.getInteger(ctx, "count"),
                                StringArgumentType.getString(ctx, "request"))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> link(String name, int slot) {
        return Commands.literal(name).executes(ctx -> run(ctx, (m, s) -> m.linkBuild(s, slot, null)))
                .then(Commands.argument("id", StringArgumentType.greedyString()).executes(ctx -> run(ctx,
                        (m, s) -> m.linkBuild(s, slot, StringArgumentType.getString(ctx, "id")))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> partCorner(int corner) {
        return Commands.literal(Integer.toString(corner)).executes(ctx -> run(ctx, (m, s) -> m.partCorner(s, corner)))
                .then(Commands.argument("position", BlockPosArgument.blockPos()).executes(ctx -> run(ctx,
                        (m, s) -> m.partCorner(s, corner, BlockPosArgument.getBlockPos(ctx, "position")))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> zoneCorner(int corner) {
        return Commands.literal(Integer.toString(corner))
                .executes(ctx -> run(ctx, (m, s) -> m.zoneCorner(s, corner)))
                .then(Commands.argument("position", BlockPosArgument.blockPos()).executes(ctx -> {
                    var pos = BlockPosArgument.getBlockPos(ctx, "position");
                    return run(ctx, (m, s) -> m.zoneCorner(s, corner, pos));
                }));
    }

    /** The singleplayer world owner (cheats not needed), or gamemaster permission (ops, console, RCON). */
    public static boolean allowed(CommandSourceStack source) {
        if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
    }

    private static int run(CommandContext<CommandSourceStack> ctx, BiConsumer<BuildSessionManager, CommandSourceStack> action) {
        BuildSessionManager manager = JustBuildMod.manager();
        if (manager == null) {
            ctx.getSource().sendSystemMessage(Chat.error("JustBuild is not running on this server."));
            return 0;
        }
        // Builds belong to whoever runs the command; a mob or armor stand made to run it would own builds nobody can
        // manage. Players and the console (with RCON and command blocks) only.
        if (ctx.getSource().getEntity() != null && ctx.getSource().getPlayer() == null) {
            ctx.getSource().sendSystemMessage(Chat.error("JustBuild commands are for players and the console, not other entities."));
            return 0;
        }
        action.accept(manager, ctx.getSource());
        return 1;
    }
}
