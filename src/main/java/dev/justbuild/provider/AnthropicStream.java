package dev.justbuild.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Iterator;
import java.util.stream.Stream;

/** Parses SSE frames and requires message_stop, so a disconnected partial plan is never accepted. */
final class AnthropicStream {
    private final StringBuilder content = new StringBuilder();
    private final ProgressListener progress;
    private String stopReason = "";
    private long input;
    private long output;
    private int operations;
    private boolean writing;
    private boolean stopped;

    private AnthropicStream(ProgressListener progress) { this.progress = progress; }

    static String read(Stream<String> lines, ProgressListener progress) throws ProviderException {
        AnthropicStream reader = new AnthropicStream(progress);
        StringBuilder data = new StringBuilder();
        for (Iterator<String> it = lines.iterator(); !reader.stopped && it.hasNext();) {
            String line = it.next();
            if (line.isEmpty()) {
                reader.event(data.toString());
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            }
        }
        if (!reader.stopped && !data.isEmpty()) reader.event(data.toString());
        if (!reader.stopped) throw new ProviderException("The Anthropic stream ended before the response finished. Please retry.");
        AnthropicMessages.checkStop(reader.stopReason);
        if (reader.content.toString().isBlank()) throw new ProviderException("Endpoint returned an empty message");
        progress.tokens(reader.input, reader.output);
        return reader.content.toString();
    }

    private void event(String data) throws ProviderException {
        if (data.isBlank()) return;
        try {
            JsonObject event = JsonParser.parseString(data).getAsJsonObject();
            progress.activity();
            switch (AnthropicMessages.string(event, "type")) {
                case "error" -> throw AnthropicMessages.error(event);
                case "message_start" -> {
                    JsonObject message = event.getAsJsonObject("message");
                    if (message.get("usage") instanceof JsonObject usage) {
                        input = AnthropicMessages.inputTokens(usage);
                        output = AnthropicMessages.count(usage, "output_tokens");
                    }
                }
                case "content_block_start" -> {
                    JsonObject block = event.getAsJsonObject("content_block");
                    if (AnthropicMessages.string(block, "type").equals("text"))
                        append(AnthropicMessages.string(block, "text"));
                }
                case "content_block_delta" -> {
                    JsonObject delta = event.getAsJsonObject("delta");
                    switch (AnthropicMessages.string(delta, "type")) {
                        case "text_delta" -> append(AnthropicMessages.string(delta, "text"));
                        case "thinking_delta" -> { if (!writing) progress.phase(ProgressListener.Phase.THINKING); }
                        default -> { /* Forward-compatible event types, including signatures. */ }
                    }
                }
                case "message_delta" -> {
                    JsonObject delta = event.getAsJsonObject("delta");
                    if (!AnthropicMessages.string(delta, "stop_reason").isEmpty())
                        stopReason = AnthropicMessages.string(delta, "stop_reason");
                    if (event.get("usage") instanceof JsonObject usage) {
                        // Counts are cumulative; later deltas may contain output usage only.
                        if (usage.has("output_tokens")) output = AnthropicMessages.count(usage, "output_tokens");
                    }
                }
                case "message_stop" -> stopped = true;
                default -> { /* Pings and future event types carry no plan text. */ }
            }
        } catch (RuntimeException e) {
            throw new ProviderException("Endpoint returned an invalid Anthropic stream event");
        }
    }

    private void append(String text) {
        if (text.isEmpty()) return;
        if (!writing) { writing = true; progress.phase(ProgressListener.Phase.WRITING); }
        int from = content.length() - 3;
        content.append(text);
        int found = ProgressListener.countOps(content, from);
        if (found > 0) { operations += found; progress.operations(operations); }
    }
}
