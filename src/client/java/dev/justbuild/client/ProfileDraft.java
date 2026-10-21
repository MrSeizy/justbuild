package dev.justbuild.client;

import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ProfileEdits;

/** A profile being edited in the panel. The saved key never reaches the client; only whether there is one. */
final class ProfileDraft {
    /** Name when the editor opened, so the server can keep that profile's key; null for a new profile. */
    final String from;
    final boolean hasKey;
    /** Base URL the saved key belongs to; the server also checks its original provider. */
    final String savedUrl;
    final String savedProvider;
    String name;
    String provider;
    String model;
    String baseUrl;
    /** Sent as reasoning_effort; null leaves it to the endpoint. */
    String reasoningEffort;
    String maxTokens;
    /** What was typed in the key box; blank keeps the saved key. */
    String typedKey = "";
    boolean clearKey;
    /** Copies and New are unsaved profiles even before any field changes. */
    private boolean added;
    private final String initial;

    ProfileDraft(String from, String name, String provider, String model, String baseUrl, boolean hasKey,
            String reasoningEffort) {
        this(from, name, provider, model, baseUrl, hasKey, reasoningEffort, null);
    }

    ProfileDraft(String from, String name, String provider, String model, String baseUrl, boolean hasKey,
            String reasoningEffort, Integer maxTokens) {
        this.from = from;
        this.name = name;
        this.provider = provider;
        this.model = model;
        this.baseUrl = baseUrl == null ? JustBuildConfig.defaultBaseUrl(provider) : baseUrl;
        this.reasoningEffort = JustBuildConfig.ModelProfile.reasoningEffort(reasoningEffort);
        this.maxTokens = String.valueOf(maxTokens == null ? JustBuildConfig.DEFAULT_MAX_TOKENS : maxTokens);
        this.savedUrl = this.baseUrl;
        this.savedProvider = provider;
        this.hasKey = hasKey;
        this.initial = state();
    }

    static ProfileDraft of(ClientState.Profile p) {
        return new ProfileDraft(p.name(), p.name(), p.provider(), p.model(), p.baseUrl(), p.hasKey(),
                p.reasoningEffort(), p.maxTokens());
    }

    /** Same settings under a new name; it keeps the original's saved key. */
    ProfileDraft copy(String newName) {
        ProfileDraft d = new ProfileDraft(from, newName, savedProvider, model, savedUrl, hasKey, reasoningEffort);
        d.provider = provider;
        d.maxTokens = maxTokens;
        d.baseUrl = baseUrl;
        d.typedKey = typedKey;
        d.clearKey = clearKey;
        d.added = true;
        return d;
    }

    /** Every editable field; a typed key only counts as typed, so the text itself is never kept here. */
    String state() {
        return String.join("\n", String.valueOf(from), name, provider, String.valueOf(model), baseUrl,
                String.valueOf(reasoningEffort), maxTokens, String.valueOf(!typedKey.isEmpty()), String.valueOf(clearKey));
    }

    /** New, copied, or changed since the editor opened. */
    boolean edited() {
        return from == null || added || !state().equals(initial);
    }

    boolean isOpenAi() {
        return "openai".equals(provider);
    }

    boolean isApi() { return JustBuildConfig.isApi(provider); }

    void provider(String value) {
        if (baseUrl.isBlank() || baseUrl.equals(JustBuildConfig.defaultBaseUrl(provider)))
            baseUrl = JustBuildConfig.defaultBaseUrl(value);
        if (!provider.equals(value)) typedKey = "";
        provider = value;
    }

    int outputTokens() {
        try { return Integer.parseInt(maxTokens.strip()); } catch (NumberFormatException e) { return 0; }
    }

    /** Whether the saved key still applies: it is kept only for the provider and URL it was saved with. */
    boolean keepsSavedKey() {
        return hasKey && !clearKey && provider.equals(savedProvider) && ProfileEdits.sameUrl(baseUrl, savedUrl);
    }

    /** True when the server will have a key for this profile after saving. */
    boolean willHaveKey() {
        return !typedKey.isBlank() || keepsSavedKey();
    }

    ProfileEdits.Edit toEdit() {
        String key = clearKey && typedKey.isBlank() ? "" : typedKey.isBlank() ? null : typedKey.strip();
        return new ProfileEdits.Edit(from, name.strip(), provider, model.strip(), isApi() ? baseUrl.strip() : null,
                isApi() ? key : null, isOpenAi() ? reasoningEffort : null,
                "anthropic".equals(provider) ? outputTokens() : null);
    }
}
