package dev.justbuild.client;

import dev.justbuild.command.Chat;
import dev.justbuild.net.BuildRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Submission is the sole place where editor text becomes authorized for a player AI relay. */
final class PromptSender {
    private PromptSender() { }
    static String send(String target, String question, int options, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !ClientPlayNetworking.canSend(BuildRequestPayload.TYPE))
            return "The server must run the current JustBuild version to use the request editor.";
        if (question != null && !question.equals(ClientState.question())) return "That question changed. Reopen the editor to answer it.";
        try {
            var payload = new BuildRequestPayload(question != null ? "answer" : target != null ? "edit" : "build",
                    question != null || target == null ? "" : target, question != null ? 1 : options, text,
                    BuildRequestPayload.questionKey(question));
            ClientAi.editorConsent(payload);
            ClientPlayNetworking.send(payload);
            return null;
        } catch (IllegalArgumentException e) { return e.getMessage(); }
    }
}
