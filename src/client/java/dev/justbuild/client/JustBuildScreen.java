package dev.justbuild.client;

import dev.justbuild.command.Chat;
import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.net.ProfilesPayload;
import dev.justbuild.prompt.Guidance;
import dev.justbuild.prompt.TemplateLibrary;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The JustBuild panel. Every action is an ordinary /justbuild command, so permissions and feedback stay on the
 * server. It does not pause the game: an integrated server keeps building while the panel is open.
 */
public final class JustBuildScreen extends FormScreen {
    private enum Tab { CREATE, TEMPLATES, HISTORY, DESIGNS, WORLD, SETTINGS }

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int GOLD = 0xFFFFAA00;
    private static final int MAX_REQUEST = dev.justbuild.net.BuildRequestPayload.MAX_TEXT;

    // Kept across openings so a half-typed request or the chosen tab survives closing the panel.
    private static Tab tab = Tab.CREATE;
    private static String draft = "";
    private static String filter = "";
    private static int page;
    private static int optionCount = 1;
    /** The build the request box was last written for; a different selection starts with an empty box. */
    private static String draftFor;
    private static String designFilter = "";
    private static String designFolder = "All folders";
    private List<ClientState.Design> shownDesigns = List.of();
    private int designRows;

    private EditBox request;
    /** The Create log's lines as last drawn, so a click on a [button] in them can run it. */
    private List<FormattedCharSequence> shownLines = List.of();
    private int shownTop;
    /** The Templates filter; it rebuilds the page on every keystroke and must keep the focus while it does. */
    private EditBox filterBox;
    private boolean refocusFilter;
    /** Settings footer on a remote server, worked out when the tab is built rather than every frame. */
    private String myAiFooter = "";
    private int left;
    private int panelWidth;
    private int contentTop;
    private int contentBottom;
    private final TextDraft modelDraft = new TextDraft();
    private int historyPage;

    public JustBuildScreen() {
        super(Component.literal("JustBuild"));
    }

    @Override public void removed() { PromptDrafts.flush(); }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        filterBox = null;
        panelWidth = Math.min(width - 20, 440);
        left = (width - panelWidth) / 2;
        int top = 22;
        request = new GlassEditBox(font, left, top, panelWidth - 94, 20, Component.literal("Request"), Glass.CUBE);
        request.setMaxLength(MAX_REQUEST);
        String target = requestTarget();
        boolean revising = PromptSender.REVISE.equals(target);
        boolean editing = !revising && target != null;
        boolean answering = ClientState.question() != null;
        draftFor = PromptDrafts.key(target, ClientState.question());
        draft = PromptDrafts.get(draftFor);
        request.setHint(Component.literal(answering ? "Type your answer…" : revising ? "Describe a change to this preview…"
                : editing ? "Describe the change…" : "Describe what you want to build…"));
        request.setValue(draft.replace('\n', ' '));
        String key = draftFor;
        boolean multiline = draft.indexOf('\n') >= 0;
        request.setEditable(!multiline);
        if (multiline) request.setTooltip(Tooltip.create(Component.literal("Multiline draft: use ↗ to edit while keeping its line breaks.")));
        request.setResponder(value -> { if (!multiline) { draft = value; PromptDrafts.put(key, value); } });
        addRenderableWidget(request);
        addRenderableWidget(GlassButton.builder(Component.literal("↗"), b -> minecraft.gui.setScreen(new PromptEditorScreen(this,
                target, ClientState.question(), revising ? 1 : optionCount, () -> { optionCount = 1; tab = Tab.CREATE; })))
                .bounds(left + panelWidth - 90, top, 24, 20).tooltip(Tooltip.create(Component.literal(
                        "Expand request editor · multiple lines, character count, and reusable prompts"))).build());
        String action = answering ? "Answer" : revising ? "Revise" : (editing ? "Apply" : "Send") + (optionCount > 1 ? " ×" + optionCount : "");
        Button go = GlassButton.builder(Component.literal(action), b -> submit()).bounds(left + panelWidth - 62, top, 62, 20).primary().build();
        if (revising) {
            go.active = ClientState.planning() == null;
            go.setTooltip(Tooltip.create(Component.literal(go.active
                    ? "Ask the AI to change this preview. Nothing is built until you choose Instant Build or Guided Build."
                    : "The AI is revising this preview. Wait for it, or press Stop.")));
        } else if (!answering && ClientState.planning() != null) {
            go.setTooltip(Tooltip.create(Component.literal("Your request “" + ClientState.planning()
                    + "” is still being planned; sending a new one stops it.")));
        }
        if (ConstructionClient.active()) {
            // The server refuses every new plan while a survival project is open; say so before sending.
            go.active = false;
            go.setTooltip(Tooltip.create(Component.literal("A Guided Build project is open. End it in the construction panel ("
                    + JustBuildClient.panelKey() + ") before sending new requests.")));
        }
        addRenderableWidget(go);
        addRenderableWidget(GlassButton.builder(Component.literal("×"), b -> onClose())
                .bounds(left + panelWidth - 14, 5, 14, 14).ghost().tooltip(Tooltip.create(Component.literal("Close (Esc)"))).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Guide"), b -> minecraft.gui.setScreen(new GettingStartedScreen(this)))
                .bounds(left, 5, 48, 14).ghost().tooltip(Tooltip.create(Component.literal("From AI setup to your first confirmed build"))).build());

        int tabWidth = panelWidth / Tab.values().length;
        boolean tabIcons = true;
        for (Tab t : Tab.values()) tabIcons &= font.width(label(t)) + 12 <= tabWidth - 2 - 4;
        for (Tab t : Tab.values()) {
            addRenderableWidget(GlassButton.builder(Component.literal(label(t)), b -> {
                tab = t;
                rebuildWidgets();
            }).bounds(left + t.ordinal() * tabWidth, top + 26, tabWidth - 2, 20).build().tab(tabIcons ? icon(t) : null, t == tab));
        }
        contentTop = top + 52;
        contentBottom = height - 8;
        switch (tab) {
            case CREATE -> initCreate();
            case TEMPLATES -> initTemplates();
            case HISTORY -> initHistory();
            case DESIGNS -> initDesigns();
            case WORLD -> initWorld();
            case SETTINGS -> initSettings();
        }
        setInitialFocus(refocusFilter && filterBox != null ? filterBox : request);
        refocusFilter = false;
    }

    /** Where the request box sends: a revisable preview is revised, otherwise the selected build or a new one. */
    private static String requestTarget() {
        ClientState.Preview preview = ClientState.preview();
        if (ClientState.question() == null && preview != null && preview.revisable()) return PromptSender.REVISE;
        return ClientState.string("selectedBuild", null);
    }

    private static String label(Tab t) {
        return switch (t) {
            case CREATE -> "Create";
            case TEMPLATES -> "Templates";
            case HISTORY -> "History";
            case DESIGNS -> "Designs";
            case WORLD -> "World";
            case SETTINGS -> "Settings";
        };
    }

    private static net.minecraft.resources.Identifier icon(Tab t) {
        return Glass.id("glass/tab_" + t.name().toLowerCase(java.util.Locale.ROOT));
    }

    // ---- Create: recent messages and quick actions ----

    /** The rows below the Create tab's context line. */
    private int createRows() {
        return contentTop + 14;
    }

    private void initCreate() {
        int half = (panelWidth - 4) / 2;
        int rows = createRows();
        ClientState.Preview waiting = ClientState.preview();
        if (waiting != null) initPreview(waiting);
        else {
        String zone = ClientState.string("zoneLabel", null);
        if (zone != null) {
            addRenderableWidget(GlassButton.builder(Component.literal("Clear zone"), b -> run("justbuild zone clear"))
                    .bounds(left + panelWidth - 64, contentTop - 2, 64, 12).tooltip(Tooltip.create(Component.literal(
                            "Build in front of you (or where you look) instead of inside this zone"))).build());
        }
        addRenderableWidget(GlassButton.builder(Component.literal("New build"), b -> {
            run("justbuild select clear");
        }).bounds(left, rows, half, 20).tooltip(Tooltip.create(Component.literal(
                "Stop editing the selected build; the next request makes a new one"))).build());
        String selectedId = ClientState.string("selectedBuild", null);
        Button manage = GlassButton.builder(Component.literal("Manage build"), b ->
                minecraft.gui.setScreen(new BuildDetailsScreen(this, selectedId)))
                .bounds(left + half + 4, rows, half, 20).tooltip(Tooltip.create(Component.literal(selectedId == null
                        ? "Select a build first: History, or sneak-right-click it with the Builder Wand" : "Rename, checkpoints, designs, and more"))).build();
        manage.active = selectedId != null;
        addRenderableWidget(manage);
        if (ClientState.string("retainedPlan", null) != null) {
            addRenderableWidget(GlassButton.builder(Component.literal("Retry saved plan"), b -> run("justbuild retry"))
                    .bounds(left, rows + 24, half, 20).tooltip(Tooltip.create(Component.literal(
                            "Preview the kept plan again, without another AI call"))).build());
            addRenderableWidget(GlassButton.builder(Component.literal("Equip wand: Zone"), b -> {
                run("justbuild zone wand");
                onClose();
            }).bounds(left + half + 4, rows + 24, half, 20).tooltip(Tooltip.create(Component.literal(
                    "Move a corner with its in-world handle, then retry the saved plan."))).build());
        } else {
            int third = (panelWidth - 8) / 3;
            addRenderableWidget(GlassButton.builder(Component.literal("Options: " + optionCount), b -> {
                optionCount = optionCount % 3 + 1;
                rebuildWidgets();
            }).bounds(left, rows + 24, third, 20).tooltip(Tooltip.create(Component.literal(
                    "Plan one, two, or three alternatives for your next request (each uses its own AI calls). Goes back to 1 after you send it."))).build());
            addRenderableWidget(GlassButton.builder(Component.literal("Editing tools"), b -> minecraft.gui.setScreen(new EditingToolsScreen(this)))
                    .bounds(left + third + 4, rows + 24, third, 20).tooltip(Tooltip.create(Component.literal(
                            "Parts, locked areas, and connections"))).build());
            addRenderableWidget(GlassButton.builder(Component.literal("Workshop"), b -> minecraft.gui.setScreen(new WorkshopScreen(this)))
                    .bounds(left + 2 * (third + 4), rows + 24, third, 20).tooltip(Tooltip.create(Component.literal(
                            "Add a building you made by hand, swap materials, repair, plan interiors, and village projects"))).build());
        }
        int extra = rows + 48;
        if (firstBuildHelp()) addRenderableWidget(GlassButton.builder(Component.literal("Start your first build →"),
                b -> minecraft.gui.setScreen(new GettingStartedScreen(this))).bounds(left, extra, panelWidth, 20).build());
        List<String> options = ClientState.question() == null ? List.of() : ClientState.questionOptions();
        if (!options.isEmpty()) {
            int count = Math.min(options.size(), 4);
            int w = (panelWidth - 4 * (count - 1)) / count;
            for (int i = 0; i < count; i++) {
                String option = options.get(i);
                addRenderableWidget(GlassButton.builder(Component.literal(option), b -> run("justbuild answer " + option))
                        .bounds(left + i * (w + 4), extra, w, 20).tooltip(Tooltip.create(Component.literal("Answer: " + option))).build());
            }
        }
        }
        if (waiting != null) {
            initBuildChoices(waiting);
            return;
        }
        int y = contentBottom - 20;
        int w = (panelWidth - 6) / 4;
        String undoTarget = ClientState.undoTarget();
        addRenderableWidget(GlassButton.builder(Component.literal("Undo"), b -> run("justbuild undo"))
                .bounds(left, y, w, 20).tooltip(Tooltip.create(Component.literal(undoTarget == null ? "Nothing to undo"
                        : "Undo your latest change: “" + undoTarget + "” (asks first)"))).build()).active = undoTarget != null;
        String undoWaiting = ClientState.undoWaiting();
        Button confirm = GlassButton.builder(Component.literal(undoWaiting != null ? "Confirm undo" : "Confirm"),
                b -> run("justbuild undo confirm")).bounds(left + w + 2, y, w, 20).tooltip(Tooltip.create(Component.literal(
                undoWaiting != null ? "Undo the change to “" + undoWaiting + "”"
                        : "Nothing is waiting"))).primary().build();
        confirm.active = undoWaiting != null;
        addRenderableWidget(confirm);
        boolean stopsWork = undoWaiting == null && (ClientState.planning() != null || ClientState.question() != null);
        String stop = undoWaiting != null ? "Keep it" : stopsWork ? "Stop request" : "Cancel";
        GlassButton.Builder stopButton = GlassButton.builder(Component.literal(stop), b -> run(undoWaiting != null ? "justbuild undo cancel" : "justbuild cancel"))
                .bounds(left + 2 * (w + 2), y, w, 20).tooltip(Tooltip.create(Component.literal(undoWaiting != null ? "Keep the change; the waiting undo is dropped"
                        : "Stop what's going on: a request or a placement")));
        if (stopsWork) stopButton.danger();
        addRenderableWidget(stopButton.build());
        addRenderableWidget(GlassButton.builder(Component.literal("Status"), b -> run("justbuild status"))
                .bounds(left + 3 * (w + 2), y, w, 20).build());
    }

    /** A completed preview offers the two placement paths together, separate from sending a prompt; a revision pauses them. */
    private void initBuildChoices(ClientState.Preview preview) {
        int y = contentBottom - 20, buildWidth = Math.min(96, (panelWidth - 168) / 2);
        // With a preview waiting, any running request or pending question belongs to its revision.
        boolean revisingNow = ClientState.planning() != null || ClientState.question() != null;
        addRenderableWidget(GlassButton.builder(Component.literal("Instant Build"), b -> run("justbuild confirm"))
                .bounds(left, y, buildWidth, 20).primary()
                .tooltip(Tooltip.create(Component.literal(revisingNow ? "Wait for the revision, or press Stop."
                        : "Place this preview immediately using the normal build permissions."))).build()).active = !revisingNow;
        addRenderableWidget(GlassButton.builder(Component.literal("Guided Build"), b -> minecraft.gui.setScreen(new ConstructionSetupScreen(this)))
                .bounds(left + buildWidth + 4, y, buildWidth, 20).primary()
                .tooltip(Tooltip.create(Component.literal(revisingNow ? "Wait for the revision, or press Stop."
                        : preview.movable() ? "Choose Guide, Assist, or Cobble and build with real materials."
                        : "Guided Build is available for new buildings and design copies."))).build()).active = preview.movable() && !revisingNow;
        String undoTarget = ClientState.undoTarget();
        addRenderableWidget(GlassButton.builder(Component.literal("Undo"), b -> run("justbuild undo"))
                .bounds(left + panelWidth - 156, y, 44, 20)
                .tooltip(Tooltip.create(Component.literal(undoTarget == null ? "Nothing to undo" : "Undo your latest change: “" + undoTarget + "” (asks first)")))
                .build()).active = undoTarget != null && !revisingNow;
        if (revisingNow) {
            addRenderableWidget(GlassButton.builder(Component.literal("Stop"), b -> run("justbuild cancel"))
                    .bounds(left + panelWidth - 108, y, 48, 20).danger()
                    .tooltip(Tooltip.create(Component.literal("Stop the revision; the preview stays as it is"))).build());
        } else {
            addRenderableWidget(GlassButton.builder(Component.literal("Status"), b -> run("justbuild status"))
                    .bounds(left + panelWidth - 108, y, 48, 20).build());
        }
        addRenderableWidget(GlassButton.builder(Component.literal("Discard"), b -> run("justbuild preview discard"))
                .bounds(left + panelWidth - 56, y, 56, 20).danger()
                .tooltip(Tooltip.create(Component.literal("Discard the waiting preview (kept 30 minutes: Retry saved plan brings it back)"))).build());
    }

    private boolean firstBuildHelp() {
        return ClientState.known() && ClientState.builds().isEmpty() && ClientState.preview() == null
                && ClientState.planning() == null && ClientState.question() == null && ClientState.placing() == null
                && ClientState.string("selectedBuild", null) == null;
    }

    void showSettings() { tab = Tab.SETTINGS; minecraft.gui.setScreen(this); }
    void showCreate() { tab = Tab.CREATE; minecraft.gui.setScreen(this); }

    private void initPreview(ClientState.Preview preview) {
        int third = (panelWidth - 8) / 3, y = contentTop + 40;
        addRenderableWidget(GlassButton.builder(Component.literal("Checks (" + preview.quality().size() + ")"),
                b -> minecraft.gui.setScreen(new QualityScreen(this))).bounds(left, y, third, 20)
                .tooltip(Tooltip.create(Component.literal(preview.quality().isEmpty() ? "No warnings found"
                        : String.join("\n", preview.quality())))).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Floors"), b -> minecraft.gui.setScreen(new FloorPreviewScreen(this)))
                .bounds(left + third + 4, y, third, 20).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Workshop"), b -> minecraft.gui.setScreen(new WorkshopScreen(this)))
                .bounds(left + 2 * (third + 4), y, third, 20).build());
        GlassButton before = GlassButton.builder(Component.literal("Before"), b -> { GhostPreview.before(true); rebuildWidgets(); })
                .bounds(left, y + 24, third, 20).build();
        GlassButton after = GlassButton.builder(Component.literal("After"), b -> { GhostPreview.before(false); rebuildWidgets(); })
                .bounds(left + third + 4, y + 24, third, 20).build();
        addRenderableWidget(GhostPreview.before() ? before.current() : before);
        addRenderableWidget(GhostPreview.before() ? after : after.current());
        addRenderableWidget(GlassButton.builder(Component.literal("Show: " + GhostPreview.filter().name().toLowerCase(java.util.Locale.ROOT)), b -> {
            var values = GhostPreview.Filter.values(); GhostPreview.filter(values[(GhostPreview.filter().ordinal() + 1) % values.length]); rebuildWidgets();
        }).bounds(left + 2 * (third + 4), y + 24, third, 20).tooltip(Tooltip.create(Component.literal(
                "Cycle all changes, additions, replacements, and removals. Also applies to Floors."))).build());
        int n = preview.options().size();
        if (n > 0) for (int i = 0; i < n; i++) {
            int option = i, w = (panelWidth - (n - 1) * 4) / n;
            GlassButton choice = GlassButton.builder(Component.literal((i + 1) + " · " + preview.options().get(i)),
                    b -> run("justbuild preview option " + (option + 1))).bounds(left + i * (w + 4), y + 48, w, 20)
                    .tooltip(Tooltip.create(Component.literal("Option " + (i + 1) + " of " + n + ": " + preview.options().get(i)))).build();
            choice.active = !(ClientState.planning() != null || ClientState.question() != null);
            addRenderableWidget(i == preview.option() ? choice.current() : choice);
        }
        int row = y + (n > 0 ? 72 : 48);
        List<String> answers = ClientState.question() == null ? List.of() : ClientState.questionOptions();
        if (!answers.isEmpty()) {
            // A revision's question takes Previous's slot; Previous waits for the revision anyway.
            int count = Math.min(answers.size(), 4), w = (panelWidth - 4 * (count - 1)) / count;
            for (int i = 0; i < count; i++) {
                String option = answers.get(i);
                addRenderableWidget(GlassButton.builder(Component.literal(option), b -> run("justbuild answer " + option))
                        .bounds(left + i * (w + 4), row, w, 20).tooltip(Tooltip.create(Component.literal("Answer: " + option))).build());
            }
        } else if (preview.previous()) {
            addRenderableWidget(GlassButton.builder(Component.literal("Previous"), b -> run("justbuild preview previous"))
                    .bounds(left, row, third, 20).tooltip(Tooltip.create(Component.literal(
                            "Return to the version before the latest revision (no AI call)"))).build()).active = ClientState.planning() == null && ClientState.question() == null;
        }
    }

    // ---- Templates: filter, a page of templates that fill the request box ----

    private void initWorld() {
        int half = (panelWidth - 4) / 2;
        addRenderableWidget(GlassButton.builder(Component.literal("Equip wand: Zone"), b -> {
            run("justbuild zone wand");
            onClose();
        }).bounds(left, contentTop, half, 20).tooltip(Tooltip.create(Component.literal(
                "Equip the Builder Wand in gold Zone mode. Left-click corner 1; right-click corner 2. Sneak-right-click in the air to switch modes."))).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Polygon"), b -> minecraft.gui.setScreen(new PolygonScreen(this)))
                .bounds(left + half + 4, contentTop, half, 20).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Clear zone"), b -> run("justbuild zone clear"))
                .bounds(left, contentTop + 24, half, 20).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Inspect world"), b -> {
            run("justbuild inspect");
            tab = Tab.CREATE;
            rebuildWidgets();
        }).bounds(left + half + 4, contentTop + 24, half, 20)
                .tooltip(Tooltip.create(Component.literal("Read nearby blocks without changing them or calling the AI"))).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Editing tools · parts, locks, connections"), b -> minecraft.gui.setScreen(new EditingToolsScreen(this)))
                .bounds(left, contentTop + 48, panelWidth, 20).build());
    }

    private void drawWorld(GuiGraphicsExtractor graphics) {
        String selected = ClientState.string("zoneLabel", null);
        String status = selected != null ? "Selected: " + selected + "."
                : "true".equals(ClientState.string("selectingZone", "false")) ? "Corner 1 set. Select corner 2."
                : "No zone selected.";
        String text = status + "\nZone mode: left-click corner 1; right-click corner 2. Sneak-right-click a build to select it.\n"
                + "Gold: Zone. Purple: Part. Sneak-right-click in the air to switch.\n"
                + "Right-click a corner handle, then a block to move that corner.\n"
                + "Hold the wand to see outlines; put it away to hide them.\n"
                + "New build starts a separate building. Maximum zone: 256 × 256.";
        int y = contentTop + 74;
        for (FormattedCharSequence line : font.split(Component.literal(text), panelWidth)) {
            if (y + font.lineHeight > contentBottom) {
                break;
            }
            graphics.text(font, line, left, y, GRAY);
            y += font.lineHeight + 1;
        }
    }

    private void initTemplates() {
        filterBox = new GlassEditBox(font, left, contentTop, panelWidth, 20, Component.literal("Filter"));
        filterBox.setHint(Component.literal("Filter templates…"));
        filterBox.setValue(filter);
        filterBox.setResponder(value -> {
            if (!value.equals(filter)) {
                filter = value;
                page = 0;
                refocusFilter = true;
                rebuildWidgets();
            }
        });
        addRenderableWidget(filterBox);
        List<TemplateLibrary.Template> templates = TemplateLibrary.defaults().filter(filter);
        int rows = Math.max(1, (contentBottom - 26 - (contentTop + 24)) / 22);
        int perPage = rows * 2;
        int pages = Math.max(1, (templates.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        int w = (panelWidth - 4) / 2;
        for (int i = 0; i < perPage && page * perPage + i < templates.size(); i++) {
            TemplateLibrary.Template t = templates.get(page * perPage + i);
            int x = left + (i % 2) * (w + 4);
            int y = contentTop + 24 + (i / 2) * 22;
            addRenderableWidget(GlassButton.builder(Component.literal(t.name()), b -> {
                run("justbuild select clear");
                PromptDrafts.put(PromptDrafts.key(null, null), t.request());
            }).bounds(x, y, w, 20).tooltip(Tooltip.create(Component.literal(t.category() + ": " + t.request()))).build());
        }
        if (pages > 1) {
            int y = contentBottom - 20;
            Button prev = GlassButton.builder(Component.literal("◀"), b -> {
                page--;
                rebuildWidgets();
            }).bounds(left, y, 40, 20).build();
            prev.active = page > 0;
            addRenderableWidget(prev);
            Button next = GlassButton.builder(Component.literal("▶"), b -> {
                page++;
                rebuildWidgets();
            }).bounds(left + panelWidth - 40, y, 40, 20).build();
            next.active = page < pages - 1;
            addRenderableWidget(next);
        }
    }

    // ---- History: select a recent build to edit, or manage its saved versions ----

    private void initHistory() {
        List<ClientState.Build> builds = ClientState.builds();
        int rows = Math.max(1, (contentBottom - contentTop - 24) / 22);
        int pages = Math.max(1, (builds.size() + rows - 1) / rows);
        historyPage = Math.min(historyPage, pages - 1);
        for (int i = 0; historyPage * rows + i < builds.size() && i < rows; i++) {
            ClientState.Build b = builds.get(historyPage * rows + i);
            int y = contentTop + i * 22;
            int actions = 64;
            String where = b.elsewhere() == null ? "" : b.elsewhere().substring(b.elsewhere().indexOf(':') + 1).replace('_', ' ');
            addRenderableWidget(GlassButton.builder(Component.literal("“" + b.title() + "” · v" + b.versions()
                    + (where.isEmpty() ? "" : " · " + where)), x -> {
                run("justbuild select " + b.id());
                setFocused(request);
            }).bounds(left, y, panelWidth - actions - 4, 20)
                    .tooltip(Tooltip.create(Component.literal("Select this build and write an edit.\n" + b.request() + (where.isEmpty() ? ""
                            : "\nIn " + b.elsewhere() + ". Travel there to select or edit it.")))).build());
            addRenderableWidget(GlassButton.builder(Component.literal("Manage"), x -> {
                run("justbuild select " + b.id());
                minecraft.gui.setScreen(new BuildDetailsScreen(this, b.id()));
            })
                    .bounds(left + panelWidth - actions, y, actions, 20).build());
        }
        if (pages > 1) {
            addRenderableWidget(GlassButton.builder(Component.literal("Previous"), b -> { historyPage--; rebuildWidgets(); })
                    .bounds(left, contentBottom - 20, 80, 20).build()).active = historyPage > 0;
            addRenderableWidget(GlassButton.builder(Component.literal("Next"), b -> { historyPage++; rebuildWidgets(); })
                    .bounds(left + panelWidth - 80, contentBottom - 20, 80, 20).build()).active = historyPage < pages - 1;
        }
    }

    private void initDesigns() {
        filterBox = new GlassEditBox(font, left, contentTop, panelWidth - 110, 20, Component.literal("Search designs"));
        filterBox.setMaxLength(60); filterBox.setHint(Component.literal("Search saved designs…")); filterBox.setValue(designFilter);
        filterBox.setResponder(value -> {
            if (!value.equals(designFilter)) { designFilter = value; page = 0; refocusFilter = true; rebuildWidgets(); }
        });
        addRenderableWidget(filterBox);
        List<String> folders = new java.util.ArrayList<>(List.of("All folders", "Unfiled"));
        ClientState.designs().stream().map(ClientState.Design::folder).filter(f -> !f.isEmpty()).distinct().sorted().forEach(folders::add);
        addRenderableWidget(GlassButton.builder(Component.literal(font.plainSubstrByWidth(designFolder, 94)), b -> {
            designFolder = folders.get((folders.indexOf(designFolder) + 1) % folders.size()); page = 0; rebuildWidgets();
        }).bounds(left + panelWidth - 106, contentTop, 106, 20).tooltip(Tooltip.create(Component.literal("Folder: " + designFolder))).build());
        shownDesigns = ClientState.designs().stream().filter(d -> d.name().toLowerCase(java.util.Locale.ROOT)
                        .contains(designFilter.toLowerCase(java.util.Locale.ROOT)))
                .filter(d -> designFolder.equals("All folders") || designFolder.equals("Unfiled") && d.folder().isEmpty() || designFolder.equals(d.folder()))
                .sorted(java.util.Comparator.comparing(ClientState.Design::name, String.CASE_INSENSITIVE_ORDER)).toList();
        designRows = Math.max(1, (contentBottom - contentTop - 50) / 34);
        int pages = Math.max(1, (shownDesigns.size() + designRows - 1) / designRows);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < designRows && page * designRows + i < shownDesigns.size(); i++) {
            ClientState.Design design = shownDesigns.get(page * designRows + i);
            addRenderableWidget(GlassButton.builder(Component.literal(design.name()), b ->
                    minecraft.gui.setScreen(new DesignPlacementScreen(this, design)))
                    .bounds(left + 38, contentTop + 26 + i * 34, panelWidth - 38, 20)
                    .tooltip(Tooltip.create(Component.literal("Top-view thumbnail · place, rotate, organize, or export this design"))).build());
        }
        addRenderableWidget(GlassButton.builder(Component.literal("Shared library"), b -> {
            run("justbuild design refresh"); minecraft.gui.setScreen(new LibraryImportScreen(this));
        }).bounds(left + (panelWidth - 144) / 2, contentBottom - 20, 144, 20).build());
        if (pages > 1) {
            addRenderableWidget(GlassButton.builder(Component.literal("Previous"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); })
                    .bounds(left, contentBottom - 20, 80, 20).build());
            addRenderableWidget(GlassButton.builder(Component.literal("Next"), b -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); })
                    .bounds(left + panelWidth - 80, contentBottom - 20, 80, 20).build());
        }
    }

    // ---- Settings: profile, detail, style, model ----

    /** Connected to a server this game does not host: Settings shows this computer's own AI. */
    private boolean remote() {
        return !minecraft.hasSingleplayerServer();
    }

    private void initSettings() {
        if (remote()) {
            initMyAiSettings();
            return;
        }
        int w = panelWidth;
        int y = contentTop;
        boolean manage = ClientState.manage() && ClientPlayNetworking.canSend(ProfilesPayload.TYPE);
        EditBox model = new GlassEditBox(font, left, y + 72, w - 132, 20, Component.literal("Model"));
        addRenderableWidget(cycle("Profile", "profile", "profileNames", y, w - 132, name -> {
            ClientState.profiles().stream().filter(p -> p.name().equals(name)).findFirst()
                    .ifPresent(p -> {
                        ClientState.set("model", p.model());
                        ClientState.set("provider", p.provider());
                        modelDraft.receive("server:" + p.name(), p.model());
                        model.setValue(p.model());
                    });
        }));
        Button profiles = GlassButton.builder(Component.literal("Manage profiles…"), b -> minecraft.gui.setScreen(new ProfilesScreen(this, false)))
                .bounds(left + w - 128, y, 128, 20).tooltip(Tooltip.create(Component.literal(manage
                        ? "Add, copy, remove, and edit model profiles (provider, model, endpoint, API key)"
                        : "Only operators or the singleplayer world owner can edit profiles"))).build();
        profiles.active = manage;
        addRenderableWidget(profiles);
        int reviewWidth = (w - 4) / 2;
        addRenderableWidget(cycle("AI review", "detail", "details", y + 24, reviewWidth, null));
        Button reviewer = cycle("Reviewer", "reviewer", "reviewerChoices", y + 24, w - reviewWidth - 4, null);
        reviewer.setX(left + reviewWidth + 4);
        reviewer.active = manage;
        if (!manage) {
            reviewer.setTooltip(Tooltip.create(Component.literal(
                    "Only operators or the singleplayer world owner can change the reviewer")));
        }
        addRenderableWidget(reviewer);
        addRenderableWidget(cycle("Style", "style", "styles", y + 48, (w - 4) / 2, null));
        addRenderableWidget(autoBuildToggle(y + 48, w));
        model.setMaxLength(200);
        model.setHint(Component.literal(JustBuildConfig.isApi(ClientState.string("provider", "")) ? "Model (required)"
                : "Model (blank = CLI default)"));
        model.setValue(modelDraft.receive("server:" + ClientState.string("profile", ""), ClientState.string("model", "")));
        model.setResponder(modelDraft::edit);
        addRenderableWidget(model);
        Button pick = GlassButton.builder(Component.literal("Pick…"), b -> {
            ClientState.profiles().stream().filter(p -> p.name().equals(ClientState.string("profile", ""))).findFirst()
                    .ifPresent(p -> {
                        ProfileDraft d = ProfileDraft.of(p);
                        minecraft.gui.setScreen(new ModelPickerScreen(this, p.name(), p.provider(), d.toEdit(), value -> {
                            ClientState.set("model", value);
                            modelDraft.replace(value);
                            run("justbuild model " + (value.isEmpty() ? "default" : value));
                        }, false));
                    });
        }).bounds(left + w - 128, y + 72, 62, 20).tooltip(Tooltip.create(Component.literal(
                "List the models this profile offers"))).build();
        pick.active = manage;
        addRenderableWidget(pick);
        addRenderableWidget(GlassButton.builder(Component.literal("Set model"), b -> {
            String value = model.getValue().strip();
            ClientState.set("model", value);
            run("justbuild model " + (value.isEmpty() ? "default" : value));
        }).bounds(left + w - 62, y + 72, 62, 20).build());
        int bw = (w - 4) / 3;
        addRenderableWidget(GlassButton.builder(Component.literal("Doctor"), b -> minecraft.gui.setScreen(new DoctorScreen(this)))
                .bounds(left, y + 100, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Check that each profile's backend is installed and logged in"))).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Reload config"), b -> run("justbuild reload"))
                .bounds(left + bw + 2, y + 100, bw, 20).build());
        addRenderableWidget(serverAiSettings(left + 2 * (bw + 2), y + 100, bw));

    }

    /** Auto-build (this player's choice, saved with the world): results place without a preview. */
    private Button autoBuildToggle(int y, int w) {
        boolean on = "true".equals(ClientState.string("autoBuild", "false"));
        return GlassButton.builder(Component.literal("Auto-build: " + (on ? "On" : "Off")), b -> run("justbuild autobuild " + (on ? "off" : "on")))
                .bounds(left + (w - 4) / 2 + 4, y, (w - 4) / 2, 20).tooltip(Tooltip.create(Component.literal(
                        "On: AI results place as soon as they're ready, without Instant Build. Undo still works; alternatives, "
                                + "saved plans, and design copies still preview."))).build();
    }

    /**
     * Settings on a server this game does not host: this computer's profiles plan your builds when the server allows
     * it ("Use my AI"); detail, style, Doctor, and Reload still act on the server.
     */
    private void initMyAiSettings() {
        int w = panelWidth;
        int y = contentTop;
        JustBuildConfig local = LocalProfiles.load();
        JustBuildConfig.ModelProfile mine = local.active();
        myAiFooter = myAiLine(local);
        addRenderableWidget(GlassButton.builder(Component.literal("My profile: " + mine.name), b -> {
            List<String> names = local.profileNames();
            String next = names.get((names.indexOf(local.activeProfile) + 1) % names.size());
            changeLocal(c -> c.activate(next));
        }).bounds(left, y, w - 132, 20).tooltip(Tooltip.create(Component.literal(
                "The profile on this computer that plans your builds here"))).build());
        addRenderableWidget(GlassButton.builder(Component.literal("My profiles…"), b -> minecraft.gui.setScreen(new ProfilesScreen(this, true)))
                .bounds(left + w - 128, y, 128, 20).tooltip(Tooltip.create(Component.literal(
                        "Add and edit the profiles on this computer; keys never leave it"))).build());
        int half = (w - 4) / 2;
        boolean here = LocalProfiles.allowedHere(local);
        Button useHere = GlassButton.builder(Component.literal("Use my AI here: " + (here ? "On" : "Off")), b -> {
            String error = LocalProfiles.toggleHere();
            if (error != null) {
                ClientState.addLine(Chat.error(error));
            }
            ClientAi.sendHello();
            rebuildWidgets();
        }).bounds(left, y + 24, half, 20).tooltip(Tooltip.create(Component.literal("Plan your builds on this server with "
                + "your own subscription or API key. Off for every server until you turn it on: only do that for "
                + "servers you trust."))).build();
        useHere.active = LocalProfiles.serverKey() != null;
        addRenderableWidget(useHere);
        Button detail = cycle("AI review", "detail", "details", y + 24, half, null);
        detail.setX(left + half + 4);
        addRenderableWidget(detail);
        addRenderableWidget(cycle("Style", "style", "styles", y + 48, (w - 4) / 2, null));
        addRenderableWidget(autoBuildToggle(y + 48, w));
        EditBox model = new GlassEditBox(font, left, y + 72, w - 132, 20, Component.literal("Model"));
        model.setMaxLength(200);
        model.setHint(Component.literal(mine.isApi() ? "Model (required)" : "Model (blank = CLI default)"));
        model.setValue(modelDraft.receive("local:" + mine.name, mine.model));
        model.setResponder(modelDraft::edit);
        addRenderableWidget(model);
        addRenderableWidget(GlassButton.builder(Component.literal("Pick…"), b -> {
            ProfileDraft d = new ProfileDraft(mine.name, mine.name, mine.provider, mine.model, mine.baseUrl, mine.hasKey(),
                    mine.reasoningEffort, mine.maxTokens);
            minecraft.gui.setScreen(new ModelPickerScreen(this, mine.name, mine.provider, d.toEdit(),
                    value -> { modelDraft.replace(value); changeLocal(c -> c.setModel(value)); }, true));
        }).bounds(left + w - 128, y + 72, 62, 20).tooltip(Tooltip.create(Component.literal(
                "List the models your profile offers, from this computer"))).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Set model"), b -> {
            String value = model.getValue().strip();
            changeLocal(c -> c.setModel(value));
        }).bounds(left + w - 62, y + 72, 62, 20).build());
        int bw = (w - 4) / 3;
        addRenderableWidget(GlassButton.builder(Component.literal("Doctor"), b -> minecraft.gui.setScreen(new DoctorScreen(this)))
                .bounds(left, y + 100, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Check the server's profiles and which AI your builds use"))).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Reload config"), b -> run("justbuild reload"))
                .bounds(left + bw + 2, y + 100, bw, 20).build());
        addRenderableWidget(serverAiSettings(left + 2 * (bw + 2), y + 100, bw));
        addRenderableWidget(GlassButton.builder(Component.literal("Review before sending: "
                + (local.reviewAiResponses ? "On" : "Off")), b -> changeLocal(c -> c.reviewAiResponses = !c.reviewAiResponses))
                .bounds(left, y + 124, half, 20).tooltip(Tooltip.create(Component.literal(
                        "Hold each response from your AI for Send or Cancel, including guide, inspection, reviewer checks, "
                                + "and revisions. Saved on this computer for all servers; off by default."))).build());
        Button localReviewer = GlassButton.builder(Component.literal("Reviewer: "
                + local.reviewer().map(p -> p.name).orElse("Same as builder")),
                b -> changeLocal(c -> c.reviewerProfile = c.nextReviewer()))
                .bounds(left + half + 4, y + 124, w - half - 4, 20).tooltip(Tooltip.create(Component.literal(
                        "The profile on this computer that checks each draft before it is built. Same as builder uses "
                                + "your planning profile."))).build();
        localReviewer.active = local.reviewerChoices().size() >= 2;
        addRenderableWidget(localReviewer);
    }

    private Button serverAiSettings(int x, int y, int w) {
        return GlassButton.builder(Component.literal("Server AI…"), b -> minecraft.gui.setScreen(new ServerAiScreen(this)))
                .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.literal(
                        "Choose whether remote players must use their own AI or may use the server's profile"))).build();
    }

    /** Saves a change to this computer's profiles, tells the server, and redraws. */
    private void changeLocal(java.util.function.Consumer<JustBuildConfig> change) {
        String error = LocalProfiles.update(change);
        if (error != null) {
            ClientState.addLine(Chat.error(error));
        }
        ClientAi.sendHello();
        rebuildWidgets();
    }

    /** The Settings footer on a server this game does not host: which AI plans your builds there. */
    private static String myAiLine(JustBuildConfig local) {
        String problem = LocalProfiles.problem(local, local.active());
        boolean here = LocalProfiles.allowedHere(local);
        return switch (ClientState.string("aiSource", "server")) {
            case "player" -> "Builds here use your AI: " + ClientState.string("aiLabel", "?") + ".";
            case "none" -> "This server needs your own AI: " + (!here ? "turn on “Use my AI here”."
                    : problem != null ? problem : "update JustBuild.");
            default -> "off".equals(ClientState.string("playerAi", "off"))
                    ? "This server plans builds with its own profile “" + ClientState.string("aiLabel", "?") + "”."
                    : "Builds here use the server's profile “" + ClientState.string("aiLabel", "?") + "”"
                            + (!here ? ". Turn on “Use my AI here” to use yours." : problem != null
                                    ? ". Yours isn't ready: " + problem : ".");
        };
    }

    /**
     * A button showing "Label: value" that steps to the next allowed value and sends /justbuild <key> <value>;
     * {@code changed} (may be null) runs after the step.
     */
    private Button cycle(String label, String key, String choicesKey, int y, int w, Consumer<String> changed) {
        return GlassButton.builder(cycleLabel(label, key, ClientState.string(key, "?")), b -> {
            List<String> choices = ClientState.list(choicesKey);
            if (choices.isEmpty()) {
                return;
            }
            int next = (choices.indexOf(ClientState.string(key, "")) + 1) % choices.size();
            String value = choices.get(next);
            ClientState.set(key, value);
            b.setMessage(cycleLabel(label, key, value));
            b.setTooltip(cycleTooltip(key, value));
            run("justbuild " + key + " " + value);
            if (changed != null) {
                changed.accept(value);
            }
        }).bounds(left, y, w, 20).tooltip(cycleTooltip(key, ClientState.string(key, "?"))).build();
    }

    private static Component cycleLabel(String label, String key, String value) {
        String shown = "detail".equals(key) ? Guidance.DetailLevel.parse(value)
                .map(Guidance.DetailLevel::reviewLabel).orElse(value)
                : "reviewer".equals(key) && "same".equals(value) ? "Same as builder" : value;
        return Component.literal(label + ": " + shown);
    }

    private static Tooltip cycleTooltip(String key, String value) {
        if ("reviewer".equals(key)) {
            return Tooltip.create(Component.literal("The profile that checks each draft before it is built. "
                    + "Same as builder uses the profile that plans it."));
        }
        return "detail".equals(key) ? Guidance.DetailLevel.parse(value)
                .map(level -> Tooltip.create(Component.literal(level.reviewHint()))).orElse(null) : null;
    }

    /** New state from the server (settings, profiles, builds): redraw the tab, keeping the filter's focus. */
    void stateChanged() {
        refocusFilter = filterBox != null && filterBox.isFocused();
        rebuildWidgets();
    }

    // ---- actions ----

    private void submit() {
        String text = PromptDrafts.get(draftFor).strip();
        if (text.isEmpty()) {
            return;
        }
        String target = requestTarget();
        String error;
        if (text.startsWith("/")) {
            error = text.length() > 256 || text.contains("\n") ? "Chat commands must fit on one line (256 characters)." : null;
            if (error == null) run(text.substring(1));
        } else if (ConstructionClient.active()) error = "A Guided Build project is open. End it in the construction panel before sending new requests.";
        else if (PromptSender.REVISE.equals(target) && ClientState.planning() != null) error = "The AI is still revising this preview. Wait for it, or press Stop.";
        else error = PromptSender.send(target, ClientState.question(), PromptSender.REVISE.equals(target) ? 1 : optionCount, text);
        if (error != null) { ClientState.addLine(Chat.error(error)); return; }
        optionCount = 1;
        PromptDrafts.put(draftFor, "");
        draft = "";
        request.setValue("");
        tab = Tab.CREATE;
        rebuildWidgets();
    }

    private void run(String command) {
        if (minecraft.player != null) {
            minecraft.player.connection.sendCommand(command);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (request.isFocused() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    /** The glass sheet instead of the vanilla blur: the world stays visible around the panel. */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Glass.sheet(graphics, left, panelWidth, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        String selectedTitle = ClientState.string("selectedTitle", null);
        graphics.centeredText(font, font.plainSubstrByWidth(selectedTitle == null ? "JustBuild · New build"
                : "Editing: " + selectedTitle, panelWidth - 40), width / 2, 8, Glass.TITLE);
        if (!ClientState.known()) {
            graphics.centeredText(font, "Waiting for the server… (JustBuild must be installed on it)", width / 2,
                    contentTop + 4, GRAY);
            return;
        }
        switch (tab) {
            case CREATE -> drawCreate(graphics);
            case WORLD -> drawWorld(graphics);
            case HISTORY -> {
                if (ClientState.builds().isEmpty()) {
                    graphics.text(font, "No builds yet. Describe one above or pick a template.", left, contentTop + 4, GRAY);
                    graphics.text(font, "Built something by hand? Workshop → Add existing build makes it editable.", left,
                            contentTop + 4 + font.lineHeight + 3, GRAY);
                }
            }
            case DESIGNS -> {
                if (shownDesigns.isEmpty()) graphics.text(font,
                        "No matches. Save a build or import from Shared library.", left, contentTop + 32, GRAY);
                for (int i = 0; i < designRows && page * designRows + i < shownDesigns.size(); i++) {
                    ClientState.Design design = shownDesigns.get(page * designRows + i);
                    int y = contentTop + 26 + i * 34;
                    DesignIcon.draw(graphics, design.thumbnail(), left + 1, y, 30);
                    graphics.text(font, font.plainSubstrByWidth(design.folder().isEmpty() ? "Unfiled" : design.folder(), panelWidth - 42),
                            left + 40, y + 22, GRAY);
                }
            }
            case SETTINGS -> {
                if (remote()) {
                    int y = contentTop + 152;
                    for (FormattedCharSequence line : font.split(Component.literal(myAiFooter), panelWidth)) {
                        if (y + font.lineHeight > contentBottom) break;
                        graphics.text(font, line, left, y, GRAY);
                        y += font.lineHeight + 1;
                    }
                } else {
                    graphics.text(font, "Saved in the server's config/justbuild.json.", left, contentTop + 128, GRAY);
                }
            }
            case TEMPLATES -> {
            }
        }
    }

    private void drawCreate(GuiGraphicsExtractor graphics) {
        ClientState.Preview preview = ClientState.preview();
        if (preview != null) {
            graphics.text(font, font.plainSubstrByWidth("Preview: " + preview.title(), panelWidth), left, contentTop, GOLD);
            String dimensions = preview.width() >= 0 ? preview.width() + " wide × " + preview.height() + " tall × " + preview.depth() + " deep"
                    : preview.position();
            graphics.text(font, font.plainSubstrByWidth(dimensions, panelWidth), left, contentTop + 13, WHITE);
            String counts = preview.hasCounts() ? "+" + preview.added() + " add · " + preview.replaced() + " replace · −" + preview.removed() + " remove"
                    : "Change counts unavailable on this server";
            graphics.text(font, font.plainSubstrByWidth(counts, panelWidth), left, contentTop + 25, GRAY);
        } else graphics.text(font, font.plainSubstrByWidth(context(), panelWidth - (ClientState.string("zoneLabel", null) != null ? 70 : 0)),
                left, contentTop, GRAY);
        boolean answers = ClientState.question() != null && !ClientState.questionOptions().isEmpty();
        // Below a preview, Previous or a revision's answers take one more row (initPreview).
        int y = preview != null ? contentTop + (preview.options().isEmpty() ? 90 : 114) + (answers || preview.previous() ? 24 : 0)
                : createRows() + 50 + (answers ? 24 : 0);
        if (firstBuildHelp()) y += 24;
        if (ClientState.question() != null) {
            for (FormattedCharSequence line : font.split(Component.literal("The AI asks: " + ClientState.question()), panelWidth)) {
                graphics.text(font, line, left, y, GOLD);
                y += font.lineHeight + 1;
            }
            y += 3;
        }
        String status = ClientState.status();
        int bottom = contentBottom - 26;
        if (!status.isEmpty() && y + font.lineHeight <= bottom) {
            // The action bar names the panel key; here the panel is already open.
            String shown = status.substring(STATUS_PREFIX.length()).replace(JustBuildClient.panelKey() + " → ", "").replace("Right Shift → ", "");
            graphics.text(font, font.plainSubstrByWidth(shown, panelWidth), left, y, GOLD);
            y += font.lineHeight + 4;
        }
        List<Component> lines = ClientState.lines();
        if (lines.isEmpty() && status.isEmpty()) {
            for (var line : font.split(Component.literal("Describe a build above, or choose a starting point from Templates."), panelWidth)) {
                if (y + font.lineHeight > bottom) break;
                graphics.text(font, line, left, y, GRAY); y += font.lineHeight + 1;
            }
            return;
        }
        // Newest lines last; drop the oldest that do not fit.
        List<FormattedCharSequence> wrapped = new java.util.ArrayList<>();
        for (Component line : lines) {
            wrapped.addAll(font.split(line, panelWidth));
        }
        int fit = Math.max(0, (bottom - y) / (font.lineHeight + 1));
        shownLines = List.copyOf(wrapped.subList(Math.max(0, wrapped.size() - fit), wrapped.size()));
        shownTop = y;
        for (FormattedCharSequence line : shownLines) {
            graphics.text(font, line, left, y, WHITE);
            y += font.lineHeight + 1;
        }
    }

    /** What the next request does: "Editing “Barn” · in its zone 13 × 13 · 3 options". */
    private String context() {
        String selected = ClientState.string("selectedTitle", null);
        String zone = ClientState.string("zoneLabel", null);
        String size = zone == null ? null : zone.split(" blocks", 2)[0];
        String where = zone != null ? "inside zone " + size : selected != null ? "around the build" : "in front of you, or where you look";
        String what = selected != null ? "Editing “" + selected + "”" : "New build";
        return what + " · " + where + (optionCount > 1 ? " · " + optionCount + " options" : "");
    }

    /** A click on a [button] in the Create log runs it, as it would in chat. */
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (tab == Tab.CREATE && !shownLines.isEmpty()) {
            var finder = new net.minecraft.client.gui.ActiveTextCollector.ClickableStyleFinder(font, (int) event.x(), (int) event.y());
            int y = shownTop;
            for (FormattedCharSequence line : shownLines) {
                finder.accept(left, y, line);
                y += font.lineHeight + 1;
            }
            if (finder.result() != null
                    && finder.result().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand click) {
                var command = Chat.panelCommand(click.command());
                if (command.isPresent()) {
                    run(command.get());
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private static final String STATUS_PREFIX = "JustBuild · ";
}
