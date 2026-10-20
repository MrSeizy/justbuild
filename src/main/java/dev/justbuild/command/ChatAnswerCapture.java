package dev.justbuild.command;

import dev.justbuild.JustBuildMod;
import dev.justbuild.session.BuildSessionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** While a question is pending, the player's next chat message is the answer and is not broadcast. */
public final class ChatAnswerCapture {
    private ChatAnswerCapture() {
    }

    public static boolean allow(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
        BuildSessionManager manager = JustBuildMod.manager();
        MinecraftServer server = sender.level().getServer();
        if (manager == null || !server.isSameThread() || !manager.hasPendingQuestion(sender)) {
            return true;
        }
        // The same permission as /justbuild answer: an answer can lead straight to placement.
        if (!JustBuildCommand.allowed(sender.createCommandSourceStack())) {
            return true;
        }
        String text = message.signedContent();
        sender.sendSystemMessage(Component.literal("You: " + text).withStyle(ChatFormatting.GRAY));
        server.execute(() -> manager.answer(sender.createCommandSourceStack(), text));
        return false;
    }
}
