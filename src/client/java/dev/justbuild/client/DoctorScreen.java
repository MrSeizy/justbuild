package dev.justbuild.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.justbuild.net.DoctorPayload;
import dev.justbuild.net.DoctorRequestPayload;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** A modal report for one Doctor request. Late results never reopen it or enter a newer report. */
final class DoctorScreen extends Screen {
    private static final AtomicLong IDS = new AtomicLong();
    private final long id = IDS.incrementAndGet();
    private final Screen parent;
    private final Object connection = Minecraft.getInstance().getConnection();
    private DoctorPayload[] results = new DoctorPayload[0];
    private String summary = "Checking installed tools, login, and endpoints…";
    private boolean started, received, failed;
    private int left, top, panelWidth, panelHeight;
    private FittingMultiLineTextWidget report;
    private GlassButton copy;

    DoctorScreen(Screen parent) { super(Component.literal("Doctor results")); this.parent = parent; }
    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        if (parent.width != width || parent.height != height) parent.resize(width, height);
        panelWidth = Math.min(480, width - 28); panelHeight = Math.min(300, height - 28);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        double scroll = report == null ? 0 : report.scrollAmount();
        report = addRenderableWidget(new FittingMultiLineTextWidget(left + 10, top + 48,
                panelWidth - 26, panelHeight - 86, Component.empty(), font) {
            @Override protected void extractBackground(GuiGraphicsExtractor g) {
                Glass.field(g, getX(), getY(), getWidth(), getHeight(), isFocused());
            }
            @Override public boolean keyPressed(KeyEvent event) {
                switch (event.key()) {
                    case InputConstants.KEY_HOME -> setScrollAmount(0);
                    case InputConstants.KEY_END -> setScrollAmount(maxScrollAmount());
                    case InputConstants.KEY_PAGEUP -> setScrollAmount(scrollAmount() - getHeight());
                    case InputConstants.KEY_PAGEDOWN -> setScrollAmount(scrollAmount() + getHeight());
                    default -> { return super.keyPressed(event); }
                }
                return true;
            }
        });
        int half = (panelWidth - 24) / 2;
        copy = addRenderableWidget(GlassButton.builder(Component.literal("Copy results"), b -> ClientClipboard.set(report.getMessage().getString()))
                .bounds(left + 10, top + panelHeight - 28, half, 20).build());
        addRenderableWidget(GlassButton.builder(Component.literal("Close"), b -> onClose())
                .bounds(left + half + 14, top + panelHeight - 28, half, 20).build());
        refreshReport();
        report.setScrollAmount(scroll);
        setInitialFocus(report);
        if (!started) {
            started = true;
            if (connection != null && ClientPlayNetworking.canSend(DoctorRequestPayload.TYPE))
                ClientPlayNetworking.send(new DoctorRequestPayload(id));
            else {
                summary = "Doctor is unavailable on this connection. Reconnect and try again.";
                failed = true; received = true; refreshReport();
            }
        }
    }

    static void receive(DoctorPayload payload) {
        var mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof DoctorScreen screen) || screen.id != payload.id()
                || screen.connection != mc.getConnection()) return;
        if (payload.index() < 0) {
            screen.summary = payload.message(); screen.failed = payload.error(); screen.received = true;
            screen.results = new DoctorPayload[payload.total()];
        } else if (screen.received && screen.results.length == payload.total()) {
            screen.results[payload.index()] = payload;
        }
        screen.refreshReport();
    }

    private void refreshReport() {
        var text = Component.literal(summary).withColor((failed ? Glass.DANGER_TEXT : Glass.MUTED) & 0xFFFFFF);
        for (var result : results) if (result != null) {
            text.append(Component.literal("\n\n" + (result.error() ? "Needs attention · " : "") + result.message())
                    .withColor((result.error() ? Glass.DANGER_TEXT : Glass.PRIMARY_TEXT) & 0xFFFFFF));
        }
        report.setMessage(text);
        report.refreshScrollAmount();
        copy.active = received;
    }

    @Override public void onClose() { minecraft.gui.setScreen(connection == minecraft.getConnection() ? parent : null); }

    @Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float tick) {
        parent.extractRenderState(g, -1, -1, tick);
        g.fill(0, 0, width, height, 0x99000000);
        Glass.panel(g, left, top, panelWidth, panelHeight, 0xF014161E, Glass.PANEL_EDGE);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.centeredText(font, getTitle(), width / 2, top + 10, Glass.TITLE);
        long complete = Arrays.stream(results).filter(java.util.Objects::nonNull).count();
        String progress = !received ? "Checking server profiles…" : failed ? "Unable to run checks"
                : complete < results.length ? "Checking server profiles · " + complete + "/" + results.length + " complete"
                : "Checks complete · " + complete + " profiles";
        g.centeredText(font, progress, width / 2, top + 28, Glass.TEXT);
    }
}
