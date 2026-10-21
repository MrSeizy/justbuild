package dev.justbuild.provider;

import com.google.gson.JsonParser;
import dev.justbuild.config.JustBuildConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A small, explicit paid request, independent of build planning. Never returns provider-controlled error text. */
public final class ProfileDiagnostics {
    public enum Status { PASS, WARN, FAIL }
    public record Check(Status status, String title, String detail) {
        public Check {
            if (status == null || title == null || detail == null || title.length() > 40 || detail.length() > 240)
                throw new IllegalArgumentException("Invalid connection check");
        }
    }
    private ProfileDiagnostics() {}
    public static List<Check> run(JustBuildConfig config, JustBuildConfig.ModelProfile profile, CancellationToken token)
            throws CancelledException {
        // A diagnostic does not need the normal build-sized response allowance.
        BuildProvider provider = profile.isOpenAi() ? new OpenAiCompatibleProvider(profile, 64 * 1024)
                : profile.isAnthropic() ? new AnthropicCompatibleProvider(profile, 64 * 1024)
                : Providers.create(config, profile);
        return run(provider, profile.model, token);
    }
    static List<Check> run(BuildProvider provider, String model, CancellationToken token) throws CancelledException {
        var checks = new ArrayList<Check>();
        token.throwIfCancelled();
        boolean api = JustBuildConfig.isApi(provider.id());
        String endpoint = provider.id().equals("anthropic") ? "/messages" : "/chat/completions";
        try {
            if (api) {
                var models = provider.models(token);
                checks.add(new Check(Status.PASS, "Endpoint and authentication", "The endpoint accepted the model-list request."));
                checks.add(new Check(models.contains(model) ? Status.PASS : Status.WARN, "Model listing",
                        models.contains(model) ? "The selected model is listed. Testing it next."
                                : "The selected model is not listed. Some services hide models; the response test will check access."));
            } else {
                provider.check(token);
                checks.add(new Check(Status.PASS, "CLI and login", "The CLI is installed and its login check passed."));
            }
        } catch (CancelledException e) { throw e; }
        catch (ProviderException e) {
            if (api && (http(e, 404) || http(e, 405)))
                checks.add(new Check(Status.WARN, "Model listing", "This endpoint has no model list. Testing " + endpoint + " directly."));
            else return List.of(failure(e, api));
        } catch (RuntimeException e) { return List.of(unexpected()); }
        token.throwIfCancelled();
        if (api && (model == null || model.isBlank())) {
            checks.add(new Check(Status.FAIL, "Model", "Enter a model name or choose one with Pick…, then test again."));
            return List.copyOf(checks);
        }
        try {
            String reply = provider.complete(new PlanRequest(
                    "Return only the JSON object {\"ok\":true}. This is a connection test.",
                    "Reply with {\"ok\":true}.",
                    "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"],\"additionalProperties\":false}",
                    model, Duration.ofSeconds(20)), token);
            token.throwIfCancelled();
            boolean valid;
            try {
                var json = JsonParser.parseString(reply).getAsJsonObject();
                valid = json.size() == 1 && json.has("ok") && json.get("ok").isJsonPrimitive()
                        && json.getAsJsonPrimitive("ok").isBoolean() && json.get("ok").getAsBoolean();
            } catch (RuntimeException e) { valid = false; }
            checks.add(new Check(valid ? Status.PASS : Status.FAIL, "Model response and JSON",
                    valid ? "The selected model returned valid JSON. This profile is ready to try a build."
                            : "The response was not the requested JSON. Try another model or check the endpoint's API support."));
        } catch (CancelledException e) { throw e; }
        catch (ProviderException e) { checks.add(failure(e, api)); }
        catch (RuntimeException e) { checks.add(unexpected()); }
        return List.copyOf(checks);
    }
    private static boolean http(ProviderException e, int status) { return e.getMessage() != null && e.getMessage().startsWith("HTTP " + status + ":"); }
    private static Check failure(ProviderException e, boolean api) {
        if (http(e, 401) || http(e, 403)) return new Check(Status.FAIL, "Authentication", "The service refused access. Check the API key, account permissions and access to this model.");
        if (http(e, 404)) return new Check(Status.FAIL, "Endpoint or model", "The service could not find the chat endpoint or model. Check the base URL (often ending in /v1) and model name.");
        if (http(e, 429)) return new Check(Status.FAIL, "Service quota", "The service rate limit or quota was reached. Check your account allowance and retry later.");
        String text = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (text.contains("no response") || text.contains("timed out")) return new Check(Status.FAIL, "Response timeout", "The service did not respond in time. Check that it is running, then retry or choose a faster model.");
        if (http(e, 400) || http(e, 422)) return new Check(Status.FAIL, "Request compatibility", "The service rejected the test request. Check the model name, provider, token limit and API support.");
        return api ? new Check(Status.FAIL, "Endpoint response", "The endpoint could not complete the check. Verify the base URL, network connection and service status.")
                : new Check(Status.FAIL, "CLI or model", "Check that the configured CLI runs and is logged in on this computer. Try its default model, then test again.");
    }
    public static Check unexpected() { return new Check(Status.FAIL, "Connection check", "The check could not complete. Verify the profile settings and service, then retry."); }
    public static Check invalidDraft() { return new Check(Status.FAIL, "Profile settings", "Check the profile name, provider, base URL, model and output settings. The draft was not saved."); }
}
