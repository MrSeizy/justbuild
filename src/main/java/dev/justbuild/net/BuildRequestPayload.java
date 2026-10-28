package dev.justbuild.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A bounded request from the player's editor. It never contains an arbitrary command to execute. */
public record BuildRequestPayload(String action, String target, int options, String text, String question) implements CustomPacketPayload {
    public static final int MAX_TEXT = 4096;
    public static final Type<BuildRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("justbuild", "build_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(6), BuildRequestPayload::action,
            ByteBufCodecs.stringUtf8(8), BuildRequestPayload::target,
            ByteBufCodecs.VAR_INT, BuildRequestPayload::options,
            ByteBufCodecs.stringUtf8(MAX_TEXT), BuildRequestPayload::text,
            ByteBufCodecs.stringUtf8(64), BuildRequestPayload::question, BuildRequestPayload::new);

    public BuildRequestPayload {
        if (action == null || !java.util.Set.of("build", "edit", "answer", "revise").contains(action) || target == null || text == null || question == null
                || options < 1 || options > 3 || !target.isEmpty() && !target.matches("[0-9a-f]{8}")
                || action.equals("edit") != !target.isEmpty() || !action.equals("answer") && !question.isEmpty()
                || (action.equals("answer") || action.equals("revise")) && options != 1
                || action.equals("answer") && !question.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid build request");
        }
        text = text.strip();
        if (text.isEmpty() || text.length() > MAX_TEXT || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
            throw new IllegalArgumentException("Requests must contain 1–" + MAX_TEXT + " characters of text");
    }

    /** The same text expansion used by typed commands, only for local relay consent accounting. */
    public String consentCommand() {
        String body = (target.isEmpty() ? "" : "#" + target + " ") + text;
        return "justbuild " + (options > 1 ? "alternatives " + action + " " + options : action) + " " + body;
    }

    public static String questionKey(String question) {
        if (question == null) return "";
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(question.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
