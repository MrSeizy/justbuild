package dev.justbuild.client;

import com.google.gson.JsonParser;
import dev.justbuild.net.AiResponsePayload;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;

/** Screen checks run as part of PanelScreenshotTest; the real network/approval contract is exercised by RelayTest. */
public final class ResponseReviewChecks {
    private ResponseReviewChecks() { }

    public static dev.justbuild.provider.UsageTracker.Snapshot usage() { return ClientAi.usage(); }
    public static void limits(int calls, long tokens) {
        ClientAi.limits(new dev.justbuild.provider.UsageTracker.Limits(calls, tokens));
    }
    /** Tells the server about this game's AI again, as a Settings change does, after a test edits the config file. */
    public static void sendHello() { ClientAi.sendHello(); }

    public static void run(ClientGameTestContext context) {
        String clipboard = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
        try { checkResponse(context); }
        finally { context.runOnClient(mc -> ClientClipboard.set(clipboard)); }
    }

    private static void checkResponse(ClientGameTestContext context) {
        Screen parent = context.computeOnClient(mc -> mc.gui.screen());
        AtomicReference<ResponseReviewScreen> shown = new AtomicReference<>();
        AtomicInteger sent = new AtomicInteger(), cancelled = new AtomicInteger();
        Runnable send = () -> { sent.incrementAndGet(); shown.get().dismiss(true); };
        Runnable cancel = () -> { cancelled.incrementAndGet(); shown.get().dismiss(true); };
        String text = "{\"kind\":\"build\",\"title\":\"庭園 😀\",\"operations\":[\n"
                + "{\"op\":\"set\",\"block\":\"stone\",\"from\":[1,2,3]},\n".repeat(100)
                + "{\"op\":\"set\",\"block\":\"gold_block\",\"from\":[9,8,7]}]}";
        context.runOnClient(mc -> {
            shown.set(new ResponseReviewScreen(parent, "review.example", new AiResponsePayload(42, text, "", 1200, 200),
                    send, cancel));
            mc.gui.setScreen(shown.get());
        });
        context.takeScreenshot("response-review-long-first");
        context.runOnClient(mc -> {
            int pages = 0;
            while (button(mc.gui.screen(), "Next").active && pages++ < 100) button(mc.gui.screen(), "Next").onPress(null);
            check(pages > 1 && pages < 100, "paging did not reach the end of a long response");
            check(button(mc.gui.screen(), "Previous").active, "Previous did not become available");
        });
        context.takeScreenshot("response-review-long-last");
        context.clickScreenButton("Previous");
        context.clickScreenButton("Next");
        context.clickScreenButton("Copy response");
        String copied = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
        check(JsonParser.parseString(copied).getAsJsonObject().get("text").getAsString().equals(text),
                "copying a paged response lost or changed characters");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        check(cancelled.get() == 1 && sent.get() == 0 && context.computeOnClient(mc -> mc.gui.screen() == parent),
                "Escape did not cancel and return to the parent");

        // The maximum network size must open and copy without truncation or eagerly laying out every page.
        String maximum = "x".repeat(AiResponsePayload.MAX_CHARS - 9) + "END-😀-OK";
        context.runOnClient(mc -> {
            shown.set(new ResponseReviewScreen(parent, "review.example", new AiResponsePayload(43, maximum, "", 0, 0),
                    send, cancel));
            mc.gui.setScreen(shown.get());
        });
        context.clickScreenButton("Copy response");
        copied = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
        check(JsonParser.parseString(copied).getAsJsonObject().get("text").getAsString().equals(maximum),
                "a maximum-size response was truncated");
        context.runOnClient(mc -> mc.gui.setScreen(null));
        check(cancelled.get() == 2 && context.computeOnClient(mc -> mc.gui.screen() == null),
                "replacing the screen did not cancel without reopening its parent");
        context.runOnClient(mc -> button(shown.get(), "Send to server").onPress(null));
        check(sent.get() == 0, "a replaced review screen could still send");
        context.runOnClient(mc -> mc.gui.setScreen(parent));
    }

    private static Button button(Screen screen, String label) {
        return screen.children().stream().filter(c -> c instanceof Button b && b.getMessage().getString().equals(label))
                .map(c -> (Button) c).findFirst().orElseThrow();
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
