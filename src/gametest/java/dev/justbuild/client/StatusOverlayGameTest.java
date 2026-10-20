package dev.justbuild.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.network.chat.Component;

/** Exercises the real action-bar renderer, including its mixin, with Minecraft's loaded font. */
public final class StatusOverlayGameTest implements FabricClientGameTest {
    private static final String STATUS = "JustBuild · Codex CLI is reviewing its plan (round 1 of 1): "
            + "Confirming structural connections between the roof, walls, windows, and entrance "
            + "before checking the surrounding paths and completing the final building details · 1:24";

    @Override public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            int previousScale = context.computeOnClient(mc -> mc.options.guiScale().get());
            try {
                for (int scale : List.of(1, 2, 3)) {
                    context.runOnClient(mc -> {
                        mc.options.guiScale().set(scale);
                        mc.resizeGui();
                        mc.gui.getChat().clearMessages(false);
                        var message = Component.literal(STATUS).withStyle(ChatFormatting.GRAY);
                        List<GuiTextRenderState> rows = render(mc, message);
                        checkRows(mc, rows);
                        check(rows.size() > 1 == (mc.font.width(message) > mc.getWindow().getGuiScaledWidth() - 24),
                                "status did not wrap to the current GUI width");
                        if (!text(rows).endsWith("…")) {
                            check(text(rows).equals(STATUS), "wrapping lost message text");
                        }
                    });
                    context.waitTicks(2);
                    context.takeScreenshot("status-wrapped-scale-" + scale);
                }
                context.runOnClient(mc -> {
                    String longWord = "W".repeat(1000);
                    var rows = render(mc, Component.literal("JustBuild · " + longWord).withStyle(ChatFormatting.GRAY));
                    checkRows(mc, rows);
                    check(rows.size() == 3 && text(rows).endsWith("…"), "oversized status did not end with an ellipsis");

                    String shortStatus = "JustBuild · Placing cottage 25%";
                    rows = render(mc, Component.literal(shortStatus));
                    check(rows.size() == 1 && text(rows).equals(shortStatus), "short status changed");

                    rows = render(mc, Component.literal("Another mod · " + longWord));
                    check(rows.size() == 1 && text(rows).equals("Another mod · " + longWord), "unrelated action bar changed");

                    render(mc, Component.literal("JustBuild · " + "Checking structural connections. ".repeat(50))
                            .withStyle(ChatFormatting.GRAY));
                });
                context.waitTicks(2);
                context.takeScreenshot("status-wrapped-ellipsis");
            } finally {
                context.runOnClient(mc -> {
                    mc.gui.setOverlayMessage(Component.empty(), false);
                    mc.options.guiScale().set(previousScale);
                    mc.resizeGui();
                });
            }
        }
    }

    private static List<GuiTextRenderState> render(Minecraft mc, Component message) {
        mc.gui.setOverlayMessage(message, false);
        GuiRenderState state = new GuiRenderState();
        try {
            var extract = Gui.class.getDeclaredMethod("extractOverlayMessage", GuiGraphicsExtractor.class, DeltaTracker.class);
            extract.setAccessible(true);
            extract.invoke(mc.gui, new GuiGraphicsExtractor(mc, state, 0, 0), DeltaTracker.ZERO);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not extract the action bar", e);
        }
        List<GuiTextRenderState> rows = new ArrayList<>();
        state.forEachText(rows::add);
        if (message.getString().startsWith("JustBuild · ")) {
            List<ColoredRectangleRenderState> backgrounds = new ArrayList<>();
            state.forEachElement(element -> {
                if (element instanceof ColoredRectangleRenderState rectangle) backgrounds.add(rectangle);
            }, GuiRenderState.TraverseRange.ALL);
            check(backgrounds.size() == 1, "status should have one continuous background");
            var background = backgrounds.getFirst();
            int alpha = background.col1() >>> 24;
            check(alpha > 0 && alpha < 128 && (background.col1() & 0xFFFFFF) == 0,
                    "status background should be dark and more transparent than opaque");
            check(background.bounds().left() >= 0 && background.bounds().right() <= mc.getWindow().getGuiScaledWidth(),
                    "status background extends offscreen");
        }
        return rows;
    }

    private static void checkRows(Minecraft mc, List<GuiTextRenderState> rows) {
        check(!rows.isEmpty() && rows.size() <= 3, "unexpected number of status rows: " + rows.size());
        for (var row : rows) {
            var bounds = row.bounds();
            // Glyph quads and shadows can extend beyond the font's logical advance width.
            check(bounds != null && bounds.left() >= 4 && bounds.right() <= mc.getWindow().getGuiScaledWidth() - 4,
                    "status extends beyond screen margins: " + bounds);
            check(bounds.top() >= 0 && bounds.bottom() < mc.getWindow().getGuiScaledHeight() - 60,
                    "status extends offscreen or overlaps the hotbar");
        }
    }

    private static String text(List<GuiTextRenderState> rows) {
        List<String> lines = new ArrayList<>();
        for (var row : rows) {
            StringBuilder text = new StringBuilder();
            row.text.accept((index, style, codePoint) -> { text.appendCodePoint(codePoint); return true; });
            lines.add(text.toString().strip());
        }
        return String.join(" ", lines);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
