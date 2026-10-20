package dev.justbuild.provider;

import dev.justbuild.config.JustBuildConfig;
import java.util.List;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Any /chat/completions endpoint: OpenAI, OpenRouter, LM Studio, Ollama, vLLM, and so on. */
public final class OpenAiCompatibleProvider implements BuildProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("JustBuild");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

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
            HttpResponse<Stream<String>> response = send(httpRequest, request.timeout(), token, limitedLines());
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
        HttpResponse<Stream<String>> response = send(request, timeout, token, limitedLines());
        if (response.statusCode() / 100 != 2) {
            throw new ProviderException(OpenAiMessages.errorMessage(response.statusCode(), readError(response, timeout, token)));
        }
        return read(response, timeout, token, OpenAiCompatibleProvider::joined);
    }

    /** Like BodyHandlers.ofLines(), but read through the size limit, so even one endless line cannot fill memory. */
    private HttpResponse.BodyHandler<Stream<String>> limitedLines() {
        long max = maxResponseBytes;
        return info -> HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofInputStream(), in -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(OutputLimit.limit(in, max),
                    StandardCharsets.UTF_8));
            // Close the response stream, as ofLines() does: closing the reader would wait for a blocked read.
            return reader.lines().onClose(() -> {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // already closed
                }
            });
        });
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

    private interface LinesReader<T> {
        T read(Stream<String> lines) throws ProviderException;
    }

    /** Reads a body with a deadline and the cancel hook; a body past the size limit fails with its own message. */
    private static <T> T read(HttpResponse<Stream<String>> response, Duration timeout, CancellationToken token,
            LinesReader<T> reader) throws ProviderException {
        Stream<String> lines = response.body();
        AtomicBoolean timedOut = new AtomicBoolean();
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            timedOut.set(true);
            lines.close();
        });
        token.onCancel(lines::close);
        try {
            return reader.read(lines);
        } catch (UncheckedIOException | IllegalStateException e) {
            token.throwIfCancelled();
            if (e.getCause() instanceof OutputLimit.ExceededException tooMuch) {
                throw new ProviderException("The endpoint sent " + tooMuch.getMessage() + ", so JustBuild stopped reading it.");
            }
            if (timedOut.get()) {
                throw new ProviderException("No response after " + timeout.toSeconds() + " s");
            }
            throw new ProviderException("Connection lost while reading the response: " + e.getMessage());
        } finally {
            lines.close();
        }
    }

    /** Reads an error body with the same deadline and cancel hook as a normal response. */
    private static String readError(HttpResponse<Stream<String>> response, Duration timeout, CancellationToken token)
            throws ProviderException {
        Stream<String> lines = response.body();
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(lines::close);
        token.onCancel(lines::close);
        try {
            return joined(lines);
        } catch (UncheckedIOException | IllegalStateException e) {
            token.throwIfCancelled();
            return "(could not read the error body: " + e.getMessage() + ")";
        }
    }

    private static String joined(Stream<String> lines) {
        try (lines) {
            return lines.collect(Collectors.joining("\n"));
        }
    }

    private static <T> HttpResponse<T> send(HttpRequest request, Duration timeout, CancellationToken token,
            HttpResponse.BodyHandler<T> handler) throws ProviderException {
        token.throwIfCancelled();
        CompletableFuture<HttpResponse<T>> future = CLIENT.sendAsync(request, handler);
        token.onCancel(() -> future.cancel(true));
        try {
            return future.get(timeout.toMillis() + 5_000, TimeUnit.MILLISECONDS);
        } catch (CancellationException e) {
            throw new CancelledException();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ProviderException("No response after " + timeout.toSeconds() + " s");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw new ProviderException("No response after " + timeout.toSeconds() + " s");
            }
            String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new ProviderException("Could not reach " + request.uri().getHost() + ": " + detail);
        }
    }

    private static URI uri(String text) throws ProviderException {
        try {
            return URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Invalid base URL: " + text);
        }
    }
}
