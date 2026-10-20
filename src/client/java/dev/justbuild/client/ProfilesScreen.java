package dev.justbuild.client;

import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ProfileEdits;
import dev.justbuild.net.ProfilesPayload;
import dev.justbuild.provider.Providers;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Add, copy, remove, and edit model profiles. In singleplayer (and as LAN host) Save sends the list to the world's
 * server as a packet, never a command, since it can carry an API key. On a server this game does not host it edits
 * this computer's profiles directly ({@code local}), so keys never leave the computer. Opened from Settings.
 */
public final class ProfilesScreen extends FormScreen {
    /** OpenAI-compatible endpoints the Preset button fills in. */
    static final List<String[]> PRESETS = List.of(
            new String[] {"OpenAI", "https://api.openai.com/v1"},
            new String[] {"OpenRouter", "https://openrouter.ai/api/v1"},
            new String[] {"DeepSeek", "https://api.deepseek.com/v1"},
            new String[] {"Groq", "https://api.groq.com/openai/v1"},
            new String[] {"Mistral", "https://api.mistral.ai/v1"},
            new String[] {"xAI", "https://api.x.ai/v1"},
            new String[] {"Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"},
            new String[] {"Qwen (Token Plan)", "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1"},
            new String[] {"Experiential Labs", "https://api.experientiallabs.ai/v1"},
            new String[] {"Ollama (local)", "http://localhost:11434/v1"},
            new String[] {"LM Studio (local)", "http://localhost:1234/v1"},
            new String[] {"Custom", ""});

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int RED = 0xFFFF5555;
    private static final int LIST_WIDTH = 120;
    private static final int LABEL_WIDTH = 52;
    /** Row pitch of the list and the form, so the hints below an OpenAI-compatible form clear Save at 480 px tall. */
    private static final int ROW = 22;

    private record Label(String text, int x, int y) {
    }

    private final Screen parent;
    private final boolean local;
    private final List<ProfileDraft> drafts = new ArrayList<>();
    private ProfileDraft active;
    private int selected;
    private int firstVisible;
    private String pageLabel = "";
    private boolean showKey;
    private String message = "";
    /** The editor's state when it opened, and when Cancel last warned about unsaved changes. */
    private final String opened;
    private String warned;
    private final List<Button> listButtons = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private int left;
    private int panelWidth;

    public ProfilesScreen(Screen parent, boolean local) {
        super(Component.literal(local ? "JustBuild · My profiles (this computer)" : "JustBuild · Model profiles"));
        this.parent = parent;
        this.local = local;
        List<ClientState.Profile> source;
        String current;
        if (local) {
            JustBuildConfig config = LocalProfiles.load();
            source = config.profiles.stream().map(p -> new ClientState.Profile(p.name, p.provider, p.model, p.baseUrl,
                    p.hasKey(), p.reasoningEffort)).toList();
            current = config.activeProfile;
        } else {
            source = ClientState.profiles();
            current = ClientState.string("profile", "");
        }
        for (ClientState.Profile p : source) {
            ProfileDraft d = ProfileDraft.of(p);
            drafts.add(d);
            if (p.name().equals(current)) {
                active = d;
                selected = drafts.size() - 1;
            }
        }
        if (drafts.isEmpty()) {
            drafts.add(new ProfileDraft(null, "Claude", "claude", "", null, false, null));
        }
        if (active == null) {
            active = drafts.get(0);
        }
        opened = snapshot();
    }

    /** Everything Save would send, without key text. */
    private String snapshot() {
        StringBuilder out = new StringBuilder().append(drafts.indexOf(active));
        drafts.forEach(d -> out.append('\u0000').append(d.state()));
        return out.toString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 20, 440);
        left = (width - panelWidth) / 2;
        listButtons.clear();
        labels.clear();
        selected = Math.max(0, Math.min(selected, drafts.size() - 1));
        initList();
        initForm(drafts.get(selected));

        int y = height - 26;
        int formX = left + LIST_WIDTH + 8;
        int formWidth = panelWidth - LIST_WIDTH - 8;
        int bw = (formWidth - 4) / 3;
        addRenderableWidget(GlassButton.builder(Component.literal("Save"), b -> save())
                .bounds(formX, y, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Save all profiles; builds keep using the ★ profile"))).confirmation().build());
        var test = addRenderableWidget(GlassButton.builder(Component.literal("Test connection"), b ->
                minecraft.setScreen(new ProfileTestScreen(this, drafts.get(selected).toEdit(), local)))
                .bounds(formX + bw + 2, y, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Test this unsaved draft with a tiny request. It may use paid tokens; no build is created."))).build());
        test.active = local || ClientPlayNetworking.canSend(dev.justbuild.net.ProfileTestRequestPayload.TYPE);
        addRenderableWidget(GlassButton.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(formX + 2 * (bw + 2), y, formWidth - 2 * (bw + 2), 20).build());
    }

    // ---- left: the profiles, New / Copy / Delete ----

    private void initList() {
        int top = 24;
        int rows = Math.max(1, (height - 32 - top) / ROW);
        boolean paged = drafts.size() > rows;
        if (paged) rows = Math.max(1, rows - 1);
        firstVisible = selected / rows * rows;
        pageLabel = paged ? (firstVisible / rows + 1) + "/" + ((drafts.size() + rows - 1) / rows) : "";
        for (int i = firstVisible; i < drafts.size() && i < firstVisible + rows; i++) {
            int index = i;
            GlassButton b = GlassButton.builder(listLabel(drafts.get(i)), x -> {
                selected = index;
                showKey = false;
                message = "";
                rebuildWidgets();
            }).bounds(left, top + (i - firstVisible) * ROW, LIST_WIDTH, 20)
                    .tooltip(Tooltip.create(listLabel(drafts.get(i)))).build();
            if (i == selected) b.current();
            listButtons.add(b);
            addRenderableWidget(b);
        }
        if (paged) {
            int pageSize = rows;
            Button previous = GlassButton.builder(Component.literal("Prev"), b -> selectPage(firstVisible - pageSize))
                    .bounds(left, height - 48, 38, 20).tooltip(Tooltip.create(Component.literal("Previous profiles"))).build();
            previous.active = firstVisible > 0;
            addRenderableWidget(previous);
            Button next = GlassButton.builder(Component.literal("Next"), b -> selectPage(firstVisible + pageSize))
                    .bounds(left + LIST_WIDTH - 38, height - 48, 38, 20)
                    .tooltip(Tooltip.create(Component.literal("Next profiles"))).build();
            next.active = firstVisible + rows < drafts.size();
            addRenderableWidget(next);
        }
        int y = height - 26;
        int w = (LIST_WIDTH - 4) / 3;
        Button add = GlassButton.builder(Component.literal("New"), b -> {
            drafts.add(new ProfileDraft(null, uniqueName("New profile"), "openai", "", null, false, null));
            selected = drafts.size() - 1;
            message = "";
            rebuildWidgets();
        }).bounds(left, y, w, 20).tooltip(Tooltip.create(Component.literal("Add an OpenAI-compatible profile"))).build();
        add.active = drafts.size() < JustBuildConfig.MAX_PROFILES;
        addRenderableWidget(add);
        Button copy = GlassButton.builder(Component.literal("Copy"), b -> {
            drafts.add(drafts.get(selected).copy(uniqueName(drafts.get(selected).name + " copy")));
            selected = drafts.size() - 1;
            message = "";
            rebuildWidgets();
        }).bounds(left + w + 2, y, w, 20).build();
        copy.active = drafts.size() < JustBuildConfig.MAX_PROFILES;
        addRenderableWidget(copy);
        Button delete = GlassButton.builder(Component.literal("Delete"), b -> {
            ProfileDraft removed = drafts.remove(selected);
            if (removed == active) {
                active = drafts.get(0);
            }
            selected = Math.max(0, selected - 1);
            message = "";
            rebuildWidgets();
        }).bounds(left + 2 * (w + 2), y, LIST_WIDTH - 2 * (w + 2), 20).danger().build();
        delete.active = drafts.size() > 1;
        addRenderableWidget(delete);
    }

    private void selectPage(int index) {
        selected = Math.max(0, Math.min(index, drafts.size() - 1));
        showKey = false;
        message = "";
        rebuildWidgets();
    }

    private Component listLabel(ProfileDraft d) {
        return Component.literal((d == active ? "★ " : "") + (d.name.isBlank() ? "(no name)" : d.name));
    }

    // ---- right: the selected profile ----

    private void initForm(ProfileDraft d) {
        int formX = left + LIST_WIDTH + 8;
        int fieldX = formX + LABEL_WIDTH;
        int fieldWidth = panelWidth - LIST_WIDTH - 8 - LABEL_WIDTH;
        int y = 24;

        EditBox name = box(fieldX, y, fieldWidth, JustBuildConfig.MAX_NAME, d.name, "Profile name");
        name.setResponder(v -> {
            d.name = v;
            if (selected - firstVisible < listButtons.size()) {
                listButtons.get(selected - firstVisible).setMessage(listLabel(d));
            }
        });
        label("Name", formX, y);

        y += ROW;
        addRenderableWidget(GlassButton.builder(Component.literal(Providers.displayName(d.provider)), b -> {
            int next = (JustBuildConfig.PROVIDERS.indexOf(d.provider) + 1) % JustBuildConfig.PROVIDERS.size();
            d.provider = JustBuildConfig.PROVIDERS.get(next);
            message = "";
            rebuildWidgets();
        }).bounds(fieldX, y, fieldWidth, 20).tooltip(Tooltip.create(Component.literal(switch (d.provider) {
            case "codex" -> "Your ChatGPT subscription through the Codex CLI on the server's computer";
            case "grok" -> "Your Grok login through Grok Build CLI on the computer running this profile";
            case "openai" -> "Any /chat/completions endpoint with an API key, or a local server";
            default -> "Your Claude subscription through the Claude CLI on the server's computer";
        }))).build());
        label("Provider", formX, y);

        y += ROW;
        EditBox model = box(fieldX, y, fieldWidth - 64, 200, d.model,
                d.isOpenAi() ? "Model (required)" : "Model (blank = CLI default)");
        model.setResponder(v -> d.model = v);
        Button pick = GlassButton.builder(Component.literal("Pick…"), b -> {
            String problem = urlProblem(d);
            if (problem != null) {
                message = problem;
                return;
            }
            minecraft.setScreen(new ModelPickerScreen(this, d.name, d.provider, d.toEdit(), value -> d.model = value, local));
        }).bounds(fieldX + fieldWidth - 62, y, 62, 20).tooltip(Tooltip.create(Component.literal(
                local ? "Ask this computer's CLI or endpoint which models this profile offers"
                        : "Ask the server which models this profile offers"))).build();
        pick.active = local || ClientPlayNetworking.canSend(dev.justbuild.net.ModelsRequestPayload.TYPE);
        addRenderableWidget(pick);
        label("Model", formX, y);

        if (!d.isOpenAi()) {
            return;
        }
        y += ROW;
        int preset = presetIndex(d.baseUrl);
        addRenderableWidget(GlassButton.builder(Component.literal(preset < 0 ? "Custom" : PRESETS.get(preset)[0]), b -> {
            d.baseUrl = PRESETS.get((presetIndex(d.baseUrl) + 1) % PRESETS.size())[1];
            message = "";
            rebuildWidgets();
        }).bounds(fieldX, y, fieldWidth, 20).tooltip(Tooltip.create(Component.literal(
                "Fill in a known service’s base URL, or choose Custom for a blank URL"))).build());
        label("Preset", formX, y);

        y += ROW;
        EditBox url = box(fieldX, y, fieldWidth, 500, d.baseUrl, "https://…/v1");
        label("Base URL", formX, y);

        y += ROW;
        int keyWidth = fieldWidth - 2 * 46;
        EditBox key = box(fieldX, y, keyWidth, 1000, d.typedKey, keyHint(d));
        ((GlassEditBox) key).secret();
        key.addFormatter((text, offset) -> showKey ? null
                : FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        key.setResponder(v -> d.typedKey = v);
        addRenderableWidget(GlassButton.builder(Component.literal(showKey ? "Hide" : "Show"), b -> {
            showKey = !showKey;
            rebuildWidgets();
        }).bounds(fieldX + keyWidth + 2, y, 44, 20).tooltip(Tooltip.create(Component.literal(
                "Show what you typed (the saved key is never sent back to the game)"))).build());
        Button clear = GlassButton.builder(Component.literal("Clear"), b -> {
            d.clearKey = true;
            d.typedKey = "";
            rebuildWidgets();
        }).bounds(fieldX + keyWidth + 48, y, 44, 20).tooltip(Tooltip.create(Component.literal(
                "Remove the saved key when you save"))).build();
        clear.active = d.keepsSavedKey();
        addRenderableWidget(clear);
        // The saved key only applies to the URL it was saved with, so the hint follows the URL as it is typed.
        url.setResponder(v -> {
            d.baseUrl = v;
            key.setHint(Component.literal(keyHint(d)));
            clear.active = d.keepsSavedKey();
        });
        label("API key", formX, y);

        y += ROW;
        addRenderableWidget(GlassButton.builder(Component.literal(effortLabel(d.reasoningEffort)), b -> {
            List<String> efforts = JustBuildConfig.REASONING_EFFORTS;
            // List.of rejects indexOf(null), and null (the endpoint's default) comes first.
            int next = d.reasoningEffort == null ? 0 : efforts.indexOf(d.reasoningEffort) + 1;
            d.reasoningEffort = next < efforts.size() ? efforts.get(next) : null;
            rebuildWidgets();
        }).bounds(fieldX, y, fieldWidth, 20).tooltip(Tooltip.create(Component.literal(
                "Sent as reasoning_effort. None requests no reasoning where supported. Available settings depend on "
                        + "the model and endpoint; Endpoint default leaves the field out"))).build());
        label("Reasoning", formX, y);
    }

    private static String effortLabel(String effort) {
        return effort == null ? "Endpoint default"
                : Character.toUpperCase(effort.charAt(0)) + effort.substring(1);
    }

    private static String keyHint(ProfileDraft d) {
        if (d.clearKey) {
            return "Removed on save";
        }
        if (d.keepsSavedKey()) {
            return "Saved (blank keeps it)";
        }
        return d.hasKey ? "New URL: retype key" : "API key";
    }

    private EditBox box(int x, int y, int w, int maxLength, String value, String hint) {
        EditBox box = new GlassEditBox(font, x, y, w, 20, Component.literal(hint));
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        box.setHint(Component.literal(hint));
        addRenderableWidget(box);
        return box;
    }

    private void label(String text, int x, int y) {
        labels.add(new Label(text, x, y));
    }

    private static int presetIndex(String url) {
        String u = url == null ? "" : url.strip().replaceAll("/+$", "");
        for (int i = 0; i < PRESETS.size(); i++) {
            if (PRESETS.get(i)[1].equalsIgnoreCase(u)) {
                return i;
            }
        }
        return -1;
    }

    private String uniqueName(String base) {
        Set<String> names = new HashSet<>();
        drafts.forEach(d -> names.add(d.name.strip().toLowerCase(Locale.ROOT)));
        String clipped = base.length() > JustBuildConfig.MAX_NAME ? base.substring(0, JustBuildConfig.MAX_NAME) : base;
        String candidate = clipped;
        for (int n = 2; names.contains(candidate.toLowerCase(Locale.ROOT)); n++) {
            String suffix = " " + n;
            candidate = clipped.substring(0, Math.min(clipped.length(), JustBuildConfig.MAX_NAME - suffix.length()))
                    .strip() + suffix;
        }
        return candidate;
    }

    // ---- saving ----

    /** Save edits without changing which profile builds use. */
    private void save() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < drafts.size(); i++) {
            ProfileDraft d = drafts.get(i);
            String problem = d.name.isBlank() ? "Every profile needs a name."
                    : !names.add(d.name.strip().toLowerCase(Locale.ROOT)) ? "Two profiles are called “" + d.name.strip() + "”."
                    : d.isOpenAi() && d.model.isBlank() && (d == active || d.edited())
                    ? "“" + d.name.strip() + "” needs a model: press Pick… or type one."
                    : urlProblem(d);
            if (problem != null) {
                // Show the profile that needs attention while preserving the active choice (★).
                selected = i;
                message = problem;
                rebuildWidgets();
                return;
            }
        }
        List<ProfileEdits.Edit> edits = drafts.stream().map(ProfileDraft::toEdit).toList();
        String json = ProfileEdits.toJson(new ProfileEdits.Request(edits, active.name.strip()));
        if (local) {
            String error = LocalProfiles.save(new ProfileEdits.Request(edits, active.name.strip()));
            if (error != null) {
                message = error;
                return;
            }
            ClientAi.sendHello();
            minecraft.setScreen(parent);
            return;
        }
        if (json.length() > ProfilesPayload.MAX_LENGTH) {
            message = "That is too much to send at once; remove some profiles.";
            return;
        }
        if (!ClientPlayNetworking.canSend(ProfilesPayload.TYPE)) {
            message = "This server does not have JustBuild 0.5 or newer.";
            return;
        }
        ClientPlayNetworking.send(new ProfilesPayload(json));
        minecraft.setScreen(parent);
    }

    private static String urlProblem(ProfileDraft d) {
        if (!d.isOpenAi()) {
            return null;
        }
        try {
            URI uri = new URI(d.baseUrl.strip());
            String scheme = uri.getScheme();
            if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null) {
                return null;
            }
        } catch (java.net.URISyntaxException ignored) {
            // reported below
        }
        return "“" + d.name.strip() + "”: the base URL must start with http:// or https://.";
    }

    /** Cancel and Escape: with unsaved changes the first press only warns, so a typed key is not lost by accident. */
    @Override
    public void onClose() {
        String now = snapshot();
        if (!now.equals(opened) && !now.equals(warned)) {
            warned = now;
            message = "Unsaved changes: press Cancel or Esc again to discard them.";
            return;
        }
        minecraft.setScreen(parent);
    }

    /** The glass sheet instead of the vanilla blur: the world stays visible around the panel. */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Glass.sheet(graphics, left, panelWidth, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 8, Glass.TITLE);
        if (!pageLabel.isEmpty()) graphics.centeredText(font, pageLabel, left + LIST_WIDTH / 2, height - 42, GRAY);
        for (Label l : labels) {
            graphics.text(font, l.text(), l.x(), l.y() + 6, WHITE);
        }
        ProfileDraft d = drafts.get(selected);
        int formX = left + LIST_WIDTH + 8;
        int y = 24 + ROW * (d.isOpenAi() ? 7 : 3) + 2;
        if (!message.isEmpty()) {
            for (FormattedCharSequence line : font.split(Component.literal(message), panelWidth - LIST_WIDTH - 8)) {
                graphics.text(font, line, formX, y, RED);
                y += font.lineHeight + 1;
            }
            return;
        }
        String note = d.isOpenAi() && !d.willHaveKey() && !d.baseUrl.contains("localhost")
                && !d.baseUrl.contains("127.0.0.1") ? "Most hosted services need an API key." : "";
        String active = d == this.active ? "Builds use this profile."
                : "Builds use “" + this.active.name.strip() + "”. Switch profiles in Settings.";
        String where = local ? "Saved on this computer with encrypted API keys; used when a server lets players use their own AI."
                : "Saved in the server's config/justbuild.json with encrypted API keys.";
        for (String text : List.of(note, active, where)) {
            if (text.isEmpty()) {
                continue;
            }
            List<FormattedCharSequence> lines = font.split(Component.literal(text), panelWidth - LIST_WIDTH - 8);
            // On a short screen the later notes give way whole rather than run under Save.
            if (y + lines.size() * (font.lineHeight + 1) > height - 28) {
                break;
            }
            for (FormattedCharSequence line : lines) {
                graphics.text(font, line, formX, y, GRAY);
                y += font.lineHeight + 1;
            }
        }
    }
}
