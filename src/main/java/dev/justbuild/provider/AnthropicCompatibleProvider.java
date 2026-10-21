package dev.justbuild.provider;

import static dev.justbuild.provider.ApiHttp.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.justbuild.config.JustBuildConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Anthropic's native Messages API, including services exposing a compatible endpoint. */
public final class AnthropicCompatibleProvider implements BuildProvider {
    private final JustBuildConfig.ModelProfile settings;
    private final long maxResponseBytes;

    public AnthropicCompatibleProvider(JustBuildConfig.ModelProfile profile) {
        this(profile, OutputLimit.MAX_BYTES);
    }

    AnthropicCompatibleProvider(JustBuildConfig.ModelProfile profile, long maxResponseBytes) {
        settings = profile.copy();
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override public String id() { return "anthropic"; }
    @Override public String displayName() { return "Anthropic-compatible"; }

    @Override
    public String complete(PlanRequest request, CancellationToken token, ProgressListener progress)
            throws ProviderException {
        if (request.model() == null || request.model().isBlank())
            throw new ProviderException("Set a model first: Settings → Profiles → Pick… lists the models this endpoint offers.");
        boolean structured = true;
        while (true) {
            HttpRequest http = builder(uri(AnthropicMessages.endpoint(settings.baseUrl, "/messages")))
                    .timeout(request.timeout()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(AnthropicMessages.requestBody(request,
                            settings.outputTokens(), structured))).build();
            HttpResponse<Stream<String>> response = send(http, request.timeout(), token, limitedLines(maxResponseBytes));
            int status = response.statusCode();
            if (status / 100 == 2) {
                boolean events = response.headers().firstValue("content-type").orElse("")
                        .toLowerCase(Locale.ROOT).contains("text/event-stream");
                return read(response, request.timeout(), token, lines -> events ? AnthropicStream.read(lines, progress)
                        : AnthropicMessages.content(joined(lines), progress));
            }
            String body = readError(response, request.timeout(), token);
            String lower = body.toLowerCase(Locale.ROOT);
            if (status == 400 || status == 422) {
                if (request.spec() != null && (lower.contains("image") || lower.contains("vision"))
                        && !request.images().isEmpty()) {
                    progress.notice("images-unsupported", "This endpoint rejected images; continuing with text views and diagnostics.");
                    request = new PlanRequest(request.systemPrompt(), request.userContent()
                            + "\nImages are unavailable for this model. Use the text views, coordinates, and diagnostics.",
                            request.schemaJson(), request.model(), request.timeout());
                    continue;
                }
                if (structured && (lower.contains("output_config") || lower.contains("json_schema")
                        || lower.contains("schema") || lower.contains("structured"))) {
                    structured = false;
                    continue;
                }
            }
            throw new ProviderException(OpenAiMessages.errorMessage(status, body));
        }
    }

    @Override public String check(CancellationToken token) throws ProviderException {
        return settings.baseUrl + " reachable (" + models(token).size() + " models)";
    }

    @Override public List<String> models(CancellationToken token) throws ProviderException {
        Duration timeout = Duration.ofSeconds(30);
        TreeSet<String> models = new TreeSet<>();
        HashSet<String> cursors = new HashSet<>();
        String cursor = "";
        // Bound pagination as well as individual responses, even for a faulty compatible service.
        for (int page = 0; page < 10; page++) {
            String path = "/models?limit=1000" + (cursor.isEmpty() ? "" : "&after_id="
                    + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            HttpRequest http = builder(uri(AnthropicMessages.endpoint(settings.baseUrl, path))).timeout(timeout).GET().build();
            HttpResponse<Stream<String>> response = send(http, timeout, token, limitedLines(maxResponseBytes));
            if (response.statusCode() / 100 != 2)
                throw new ProviderException(OpenAiMessages.errorMessage(response.statusCode(), readError(response, timeout, token)));
            String body = read(response, timeout, token, ApiHttp::joined);
            try {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                for (var item : json.getAsJsonArray("data")) {
                    String id = AnthropicMessages.string(item.getAsJsonObject(), "id");
                    if (!id.isBlank()) models.add(id);
                    if (models.size() >= 1000) return List.copyOf(models);
                }
                if (!json.has("has_more") || !json.get("has_more").getAsBoolean()) break;
                cursor = AnthropicMessages.string(json, "last_id");
                if (cursor.isEmpty() || !cursors.add(cursor))
                    throw new ProviderException("The endpoint returned an invalid model-list cursor.");
            } catch (RuntimeException e) {
                throw new ProviderException("Endpoint returned an invalid Anthropic model list");
            }
        }
        if (models.isEmpty()) throw new ProviderException("The endpoint returned no model names. Enter a model manually.");
        return List.copyOf(models);
    }

    private HttpRequest.Builder builder(URI uri) {
        // The widely implemented Messages protocol also works with Anthropic's direct API.
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).header("anthropic-version", "2023-06-01");
        if (settings.hasKey()) builder.header("x-api-key", settings.apiKey.strip());
        return builder;
    }
}
