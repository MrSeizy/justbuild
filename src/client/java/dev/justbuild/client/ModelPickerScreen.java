package dev.justbuild.client;

import dev.justbuild.config.ProfileEdits;
import dev.justbuild.net.ModelsPayload;
import dev.justbuild.net.ModelsRequestPayload;
import dev.justbuild.provider.Providers;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Asks for a profile's models (the server's, or this computer's), then shows them filterable and paged. */
public final class ModelPickerScreen extends FormScreen {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int RED = 0xFFFF5555;
    /** Stands for an empty model, which the CLIs read as "use your default". */
    private static final String CLI_DEFAULT = "(CLI default)";
    private static int nextId = 1;

    private final Screen parent;
    private final String provider;
    private final Consumer<String> onPick;
    private final boolean local;
    private final int requestId = nextId++;
    private List<String> models;
    private String error = "";
    private String filter = "";
    private int page;
    private int left;
    private int panelWidth;

    /** {@code draft} is the profile as currently edited; the server fills in its saved key. */
    public ModelPickerScreen(Screen parent, String profileName, String provider, ProfileEdits.Edit draft,
            Consumer<String> onPick, boolean local) {
        super(Component.literal("Models for “" + profileName + "” (" + Providers.displayName(provider) + ")"));
        this.parent = parent;
        this.provider = provider;
        this.onPick = onPick;
        this.local = local;
        if (local) {
            // This computer's profile: ask this computer's CLI or endpoint; nothing goes to the server.
            LocalProfiles.fetchModels(draft, requestId);
        } else {
            ClientPlayNetworking.send(new ModelsRequestPayload(requestId, ProfileEdits.toJson(draft)));
        }
    }

    /** Called on the client thread when the server answers. */
    static void receive(ModelsPayload payload) {
        // A picker for this computer's own profiles never takes a list from the server.
        if (Minecraft.getInstance().gui.screen() instanceof ModelPickerScreen picker && !picker.local
                && picker.requestId == payload.id()) {
            picker.error = payload.error();
            picker.models = payload.error().isEmpty() ? payload.models() : List.of();
            picker.page = 0;
            picker.rebuildWidgets();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 20, 440);
        left = (width - panelWidth) / 2;
        EditBox filterBox = new GlassEditBox(font, left, 24, panelWidth, 20, Component.literal("Filter"));
        filterBox.setMaxLength(100);
        filterBox.setHint(Component.literal("Filter models…"));
        filterBox.setValue(filter);
        filterBox.setResponder(value -> {
            if (!value.equals(filter)) {
                filter = value;
                page = 0;
                rebuildWidgets();
            }
        });
        addRenderableWidget(filterBox);

        List<String> shown = shown();
        int top = 62;
        int rows = Math.max(1, (height - 32 - top) / 22);
        int perPage = rows * 2;
        int pages = Math.max(1, (shown.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        int w = (panelWidth - 4) / 2;
        for (int i = 0; i < perPage && page * perPage + i < shown.size(); i++) {
            String model = shown.get(page * perPage + i);
            addRenderableWidget(GlassButton.builder(Component.literal(model), b -> {
                onPick.accept(model.equals(CLI_DEFAULT) ? "" : model);
                onClose();
            }).bounds(left + (i % 2) * (w + 4), top + (i / 2) * 22, w, 20).build());
        }
        int y = height - 26;
        Button prev = GlassButton.builder(Component.literal("◀"), b -> {
            page--;
            rebuildWidgets();
        }).bounds(left, y, 40, 20).build();
        prev.active = page > 0;
        Button next = GlassButton.builder(Component.literal("▶"), b -> {
            page++;
            rebuildWidgets();
        }).bounds(left + 44, y, 40, 20).build();
        next.active = page < pages - 1;
        if (pages > 1) {
            addRenderableWidget(prev);
            addRenderableWidget(next);
        }
        addRenderableWidget(GlassButton.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(left + panelWidth - 80, y, 80, 20).build());
        setInitialFocus(filterBox);
    }

    /** The CLI-default entry first (CLIs only), then models matching the filter. */
    private List<String> shown() {
        if (models == null) {
            return List.of();
        }
        String f = filter.strip().toLowerCase(Locale.ROOT);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (!"openai".equals(provider) && f.isEmpty() && !models.isEmpty()) {
            out.add(CLI_DEFAULT);
        }
        models.stream().filter(m -> m.toLowerCase(Locale.ROOT).contains(f)).forEach(out::add);
        return out;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
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
        String status;
        int color = GRAY;
        if (models == null) {
            status = local ? "Asking your profile on this computer…" : "Asking the server…";
        } else if (!error.isEmpty()) {
            status = error;
            color = RED;
        } else {
            int matching = (int) models.stream().filter(m -> m.toLowerCase(Locale.ROOT)
                    .contains(filter.strip().toLowerCase(Locale.ROOT))).count();
            status = matching == models.size() ? models.size() + " models. Click one to use it."
                    : matching + " of " + models.size() + " models match.";
        }
        if (models != null && !error.isEmpty()) {
            int y = 50;
            for (var line : font.split(Component.literal(status), panelWidth)) {
                graphics.text(font, line, left, y, color);
                y += font.lineHeight + 1;
            }
            return;
        }
        graphics.text(font, font.plainSubstrByWidth(status, panelWidth), left, 50, color);
    }
}
