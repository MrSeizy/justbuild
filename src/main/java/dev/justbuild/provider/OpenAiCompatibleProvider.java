package dev.justbuild.provider;

import static dev.justbuild.provider.ApiHttp.*;

import dev.justbuild.config.JustBuildConfig;
import java.util.List;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Any /chat/completions endpoint: OpenAI, OpenRouter, LM Studio, Ollama, vLLM, and so on. */
public final class OpenAiCompatibleProvider implements BuildProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("JustBuild");
    private final JustBuildConfig.ModelProfile settings;
    private final long maxResponseBytes;

    /** Keeps its own copy, so later profile edits do not change a running request. */
    public OpenAiCompatibleProvider(JustBuildConfig.ModelProfile profile) {
        this(profile, OutputLimit.MAX_BYTES);
    }

    OpenAiCompatibleProvider(JustBuildConfig.ModelProfile profile, long maxResponseBytes) {
        this.settings = profile.copy();
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public String displayName() {
        return "OpenAI-compatible";
    }

    @Override
    public String complete(PlanRequest request, CancellationToken token, ProgressListener progress)
            throws ProviderException {
        String model = request.model() == null ? "" : request.model().trim();
        if (model.isEmpty()) {
            throw new ProviderException("Set a model first: in the panel (K), Settings → Pick… lists the ones this endpoint offers (or /justbuild models).");
        }
        URI uri = uri(OpenAiMessages.endpoint(settings.baseUrl, "/chat/completions"));
        OpenAiMessages.Format[] formats = OpenAiMessages.Format.values();
        boolean includeUsage = true;
        for (int i = 0; i < formats.length; i++) {
            HttpRequest httpRequest = builder(uri)
                    .timeout(request.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(OpenAiMessages.requestBody(request, model, formats[i],
                            includeUsage, settings.reasoningEffort)))
                    .build();
            HttpResponse<Stream<String>> response = send(httpRequest, request.timeout(), token, limitedLines(maxResponseBytes));
            int status = response.statusCode();
            if (status / 100 == 2) {
                return readBody(response, request.timeout(), token, progress);
            }
            String body = readError(response, request.timeout(), token);
            String lower = body.toLowerCase(java.util.Locale.ROOT);
            if ((status == 400 || status == 422) && request.spec() != null
                    && (lower.contains("image") || lower.contains("vision")) && !request.images().isEmpty()) {
                progress.notice("images-unsupported", "This endpoint rejected images; continuing with text views and diagnostics.");
                request = new PlanRequest(request.systemPrompt(), request.userContent()
                        + "\nImages are unavailable for this model. Use the text views, coordinates, and diagnostics.",
                        request.schemaJson(), request.model(), request.timeout());
                i--; continue;
            }
            if ((status == 400 || status == 422) && includeUsage && body.contains("stream_options")) {
                // Some servers reject the token-count option; ask again without it, same format.
                LOGGER.info("Endpoint rejected stream_options ({}); retrying without it",
                        status);
                includeUsage = false;
                i--;
                continue;
            }
            boolean formatRejected = (status == 400 || status == 422) && i < formats.length - 1;
            if (!formatRejected) {
                throw new ProviderException(OpenAiMessages.errorMessage(status, body));
            }
            LOGGER.info("Endpoint rejected response_format {} ({}); retrying with {}", formats[i],
                    status, formats[i + 1]);
        }
        throw new IllegalStateException("unreachable");
    }

    @Override
    public String check(CancellationToken token) throws ProviderException {
        String body = getModels(token);
        String model = settings.model == null || settings.model.isBlank() ? "no model set" : "model " + settings.model;
        int count;
        try {
            count = ModelCatalog.openAi(body).size();
        } catch (ProviderException e) {
            count = -1;
        }
        return settings.baseUrl + " reachable" + (count > 0 ? " (" + count + " models)" : "") + ", " + model;
    }

    @Override
    public List<String> models(CancellationToken token) throws ProviderException {
        return ModelCatalog.openAi(getModels(token));
    }

    private String getModels(CancellationToken token) throws ProviderException {
        Duration timeout = Duration.ofSeconds(30);
        HttpRequest request = builder(uri(OpenAiMessages.endpoint(settings.baseUrl, "/models"))).timeout(timeout)
                .GET().build();
        HttpResponse<Stream<String>> response = send(request, timeout, token, limitedLines(maxResponseBytes));
        if (response.statusCode() / 100 != 2) {
            throw new ProviderException(OpenAiMessages.errorMessage(response.statusCode(), readError(response, timeout, token)));
        }
        return read(response, timeout, token, ApiHttp::joined);
    }

    private HttpRequest.Builder builder(URI uri) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri);
        if (settings.apiKey != null && !settings.apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + settings.apiKey.trim());
        }
        return b;
    }

    /** Server-sent events when the endpoint streams, plain JSON when it ignores "stream". */
    private static String readBody(HttpResponse<Stream<String>> response, Duration timeout, CancellationToken token,
            ProgressListener progress) throws ProviderException {
        boolean events = response.headers().firstValue("content-type").orElse("").contains("text/event-stream");
        return read(response, timeout, token, lines -> {
            if (events) {
                return OpenAiStream.read(lines, progress);
            }
            String body = joined(lines);
            long[] usage = OpenAiMessages.usage(body);
            String text = OpenAiMessages.content(body);
            progress.tokens(usage[0], usage[1]);
            return text;
        });
    }

}
