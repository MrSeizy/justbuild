package dev.justbuild.client;

import dev.justbuild.net.BuildRequestPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraft.network.chat.Component;

/** A full-height editor. Its original target is retained even if server selection changes while typing. */
final class PromptEditorScreen extends Screen {
    private final Screen parent;
    private final String target, question, draftKey, context;
    private final int options;
    private final Runnable sent;
    private final net.minecraft.client.multiplayer.ClientPacketListener connection;
    private MultiLineEditBox editor;
    private int left, panelWidth;
    private String message = "Enter adds a line · Ctrl+Enter sends";
    PromptEditorScreen(Screen parent, String target, String question, int options, Runnable sent) {
        super(Component.literal(question != null ? "Answer the AI" : target == null ? "Describe a new build" : "Describe your changes"));
        this.parent = parent; this.target = target; this.question = question; this.options = options; this.sent = sent;
        this.connection = net.minecraft.client.Minecraft.getInstance().getConnection();
        this.draftKey = PromptDrafts.key(target, question);
        this.context = question != null ? question : target == null ? "New build" : "Editing: " + ClientState.string("selectedTitle", "#" + target);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override protected void init() {
        panelWidth = Math.min(width - 20, 540); left = (width - panelWidth) / 2;
        var previous = editor == null ? null : ((dev.justbuild.mixin.client.MultiLineEditBoxAccessor) editor).justbuild$textField();
        double scroll = editor == null ? 0 : editor.scrollAmount();
        editor = MultiLineEditBox.builder().setX(left).setY(54).setTextColor(Glass.TEXT).setShowBackground(false)
                .setShowDecorations(false).build(font, panelWidth, Math.max(36, height - 148), Component.literal("Request"));
        editor.setCharacterLimit(BuildRequestPayload.MAX_TEXT);
        editor.setValue(PromptDrafts.get(draftKey));
        editor.setValueListener(text -> PromptDrafts.put(draftKey, text));
        if (previous != null) {
            var field = ((dev.justbuild.mixin.client.MultiLineEditBoxAccessor) editor).justbuild$textField();
            int anchor = ((dev.justbuild.mixin.client.MultilineTextFieldAccessor) previous).justbuild$selectionAnchor();
            field.seekCursor(net.minecraft.client.gui.components.Whence.ABSOLUTE, anchor);
            field.setSelecting(true); field.seekCursor(net.minecraft.client.gui.components.Whence.ABSOLUTE, previous.cursor()); field.setSelecting(false);
            editor.setScrollAmount(scroll);
        }
        addRenderableWidget(editor);
        int half = (panelWidth - 4) / 2;
        button("Save prompt", left, height - 76, half, () -> {
            try { SavedPrompts.add(editor.getValue()); message = "Prompt saved on this computer."; }
            catch (java.io.IOException e) { message = e.getMessage(); }
        });
        button("Reuse prompt…", left + half + 4, height - 76, half, () -> minecraft.setScreen(new SavedPromptsScreen(this, editor::setValue)));
        button("Back", left, height - 28, half, this::onClose);
        button(question != null ? "Send answer" : options > 1 ? "Send ×" + options : "Send request", left + half + 4, height - 28, half, this::submit).primary();
        setInitialFocus(editor);
    }
    private GlassButton button(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(GlassButton.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, 20).build());
    }
    private void submit() {
        if (connection == null || minecraft.getConnection() != connection) { message = "The connection changed. Reopen the request editor."; return; }
        String error = PromptSender.send(target, question, options, editor.getValue());
        if (error != null) { message = error; return; }
        PromptDrafts.put(draftKey, ""); sent.run(); onClose();
    }
    @Override public boolean keyPressed(KeyEvent e) {
        if (e.hasControlDown() && (e.key() == GLFW.GLFW_KEY_ENTER || e.key() == GLFW.GLFW_KEY_KP_ENTER)) { submit(); return true; }
        return super.keyPressed(e);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float tick) {
        Glass.sheet(g, left, panelWidth, height);
        if (editor != null) Glass.pill(g, editor.getX(), editor.getY(), editor.getWidth(), editor.getHeight(), 0x14FFFFFF,
                editor.isFocused() ? Glass.ACCENT : 0x33FFFFFF);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.centeredText(font, getTitle(), width / 2, 12, Glass.TITLE);
        g.text(font, font.plainSubstrByWidth(context, panelWidth), left, 35, Glass.TEXT);
        g.text(font, editor.getValue().length() + " / " + BuildRequestPayload.MAX_TEXT, left, height - 90, Glass.MUTED);
        int line = height - 49;
        for (var text : font.split(Component.literal(message), panelWidth)) {
            if (line + font.lineHeight > height - 30) break;
            g.text(font, text, left, line, Glass.MUTED); line += font.lineHeight;
        }
    }
}
