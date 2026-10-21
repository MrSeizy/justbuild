package dev.justbuild.provider;

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

/** Bounded HTTP response reading shared by the API providers; redirects are never followed. */
final class ApiHttp {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private ApiHttp() {}

    /** Like BodyHandlers.ofLines(), but read through the size limit, so even one endless line cannot fill memory. */
    static HttpResponse.BodyHandler<Stream<String>> limitedLines(long max) {
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

    interface LinesReader<T> {
        T read(Stream<String> lines) throws ProviderException;
    }

    /** Reads a body with a deadline and the cancel hook; a body past the size limit fails with its own message. */
    static <T> T read(HttpResponse<Stream<String>> response, Duration timeout, CancellationToken token,
            LinesReader<T> reader) throws ProviderException {
        Stream<String> lines = response.body();
        AtomicBoolean timedOut = new AtomicBoolean();
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            timedOut.set(true);
            lines.close();
        });
        token.onCancel(lines::close);
        try {
            T result = reader.read(lines);
            token.throwIfCancelled();
            if (timedOut.get()) throw new ProviderException("No response after " + timeout.toSeconds() + " s");
            return result;
        } catch (ProviderException e) {
            token.throwIfCancelled();
            if (timedOut.get()) throw new ProviderException("No response after " + timeout.toSeconds() + " s");
            throw e;
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
    static String readError(HttpResponse<Stream<String>> response, Duration timeout, CancellationToken token)
            throws ProviderException {
        Stream<String> lines = response.body();
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(lines::close);
        token.onCancel(lines::close);
        try {
            return joined(lines);
        } catch (UncheckedIOException | IllegalStateException e) {
            token.throwIfCancelled();
            return "(could not read the error body: " + e.getMessage() + ")";
        } finally {
            lines.close();
        }
    }

    static String joined(Stream<String> lines) {
        try (lines) {
            return lines.collect(Collectors.joining("\n"));
        }
    }

    static <T> HttpResponse<T> send(HttpRequest request, Duration timeout, CancellationToken token,
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

    static URI uri(String text) throws ProviderException {
        try {
            return URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Invalid base URL: " + text);
        }
    }
}
