package dev.justbuild.client;

import com.google.gson.GsonBuilder;
import dev.justbuild.net.AiResponsePayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Read-only view of the normalized response. Only an explicit Send invokes the approval callback. */
public final class ResponseReviewScreen extends Screen {
    private final Screen parent;
    private final String server;
    private final AiResponsePayload response;
    private final Runnable send, cancel;
    private final String body;
    private final List<Integer> starts = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private int left, panelWidth, page, next;
    private GlassButton previousButton, nextButton;
    private boolean resolved;
    private static final int TEXT_TOP = 88;

    ResponseReviewScreen(Screen parent, String server, AiResponsePayload response, Runnable send, Runnable cancel) {
        super(Component.literal("Review before sending"));
        this.parent = parent;
        this.server = server;
        this.response = response;
        this.send = send;
        this.cancel = cancel;
        body = "Text:\n" + response.text() + "\n\nError: " + (response.error().isEmpty() ? "(empty)" : response.error());
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        panelWidth = Math.min(640, width - 24);
        left = (width - panelWidth) / 2;
        starts.clear();
        starts.add(0);
        page = 0;
        previousButton = button("Previous", left, height - 52, 80, () -> changePage(-1));
        nextButton = button("Next", left + panelWidth - 80, height - 52, 80, () -> changePage(1));
        int third = (panelWidth - 8) / 3;
        button("Copy response", left, height - 28, third,
                () -> minecraft.keyboardHandler.setClipboard(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting()
                        .create().toJson(response)));
        GlassButton cancelButton = button("Cancel", left + third + 4, height - 28, third, this::onClose);
        button("Send to server", left + 2 * (third + 4), height - 28, third, () -> {
            if (resolved) return;
            send.run();
        }).primary();
        // Enter arriving from the previous screen must never approve a response.
        setInitialFocus(cancelButton);
        layoutPage();
    }

    private GlassButton button(String label, int x, int y, int width, Runnable action) {
        return addRenderableWidget(GlassButton.builder(Component.literal(label), b -> action.run())
                .bounds(x, y, width, 20).build());
    }

    /** Wrap only a bounded slice, even for a maximum-size response; every character is reachable and copyable. */
    private void layoutPage() {
        lines.clear();
        int start = starts.get(page);
        int rows = Math.max(1, (height - 74 - TEXT_TOP) / (font.lineHeight + 1));
        int end = Math.min(body.length(), start + Math.max(4096, rows * panelWidth));
        if (end < body.length() && Character.isHighSurrogate(body.charAt(end - 1))) end--;
        String slice = body.substring(start, end);
        next = end;
        int[] count = {0};
        font.getSplitter().splitLines(slice, panelWidth - 12, Style.EMPTY, true, (style, from, to) -> {
            if (count[0] < rows) lines.add(slice.substring(from, to).replace("\n", "").replace("\r", ""));
            if (count[0] == rows) next = start + from;
            count[0]++;
        });
        previousButton.active = page > 0;
        nextButton.active = next < body.length();
    }

    private void changePage(int direction) {
        if (direction < 0 && page > 0) page--;
        else if (direction > 0 && next < body.length()) {
            if (starts.size() == page + 1) starts.add(next);
            page++;
        }
        layoutPage();
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0) changePage(vertical < 0 ? 1 : -1);
        return true;
    }

    @Override public void onClose() {
        if (!resolved) cancel.run();
    }

    /** An unrelated screen replacing this one also withholds the answer, without reopening the parent. */
    @Override public void removed() {
        if (!resolved) {
            resolved = true;
            cancel.run();
        }
    }

    void dismiss(boolean restoreParent) {
        if (resolved) return;
        resolved = true;
        if (minecraft != null && minecraft.screen == this) minecraft.setScreen(restoreParent ? parent : null);
    }

    @Override public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Glass.sheet(g, left, panelWidth, height);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, getTitle(), width / 2, 12, Glass.TITLE);
        g.text(font, font.plainSubstrByWidth("To: " + server, panelWidth), left, 30, Glass.TEXT);
        g.text(font, "Request " + response.id() + " · Tokens: " + response.tokensIn() + " in / "
                + response.tokensOut() + " out", left, 44, Glass.MUTED);
        g.text(font, "Send shares this response. Cancel withholds it.", left, 59, Glass.TEXT);
        g.text(font, "Exact response text and error (read-only):", left, 74, Glass.MUTED);
        Glass.field(g, left - 4, TEXT_TOP - 3, panelWidth + 8, Math.max(16, height - 74 - TEXT_TOP), false);
        int y = TEXT_TOP;
        for (String line : lines) {
            g.text(font, line, left + 2, y, Glass.TEXT);
            y += font.lineHeight + 1;
        }
        g.text(font, "Server may time out; progress has already been shared.", left, height - 67, Glass.MUTED);
        g.centeredText(font, "Page " + (page + 1) + (next == body.length() ? " · End" : ""), width / 2,
                height - 46, Glass.MUTED);
    }
}
