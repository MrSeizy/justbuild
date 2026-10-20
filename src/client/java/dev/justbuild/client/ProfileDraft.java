package dev.justbuild.client;

import dev.justbuild.config.JustBuildConfig;
import dev.justbuild.config.ProfileEdits;

/** A profile being edited in the panel. The saved key never reaches the client; only whether there is one. */
final class ProfileDraft {
    /** Name when the editor opened, so the server can keep that profile's key; null for a new profile. */
    final String from;
    final boolean hasKey;
    /** Base URL the saved key belongs to; the server drops the key if the URL changes. */
    final String savedUrl;
    String name;
    String provider;
    String model;
    String baseUrl;
    /** Sent as reasoning_effort; null leaves it to the endpoint. */
    String reasoningEffort;
    /** What was typed in the key box; blank keeps the saved key. */
    String typedKey = "";
    boolean clearKey;
    /** Copies and New are unsaved profiles even before any field changes. */
    private boolean added;
    private final String initial;

    ProfileDraft(String from, String name, String provider, String model, String baseUrl, boolean hasKey,
            String reasoningEffort) {
        this.from = from;
        this.name = name;
        this.provider = provider;
        this.model = model;
        this.baseUrl = baseUrl == null ? JustBuildConfig.DEFAULT_BASE_URL : baseUrl;
        this.reasoningEffort = JustBuildConfig.ModelProfile.reasoningEffort(reasoningEffort);
        this.savedUrl = this.baseUrl;
        this.hasKey = hasKey;
        this.initial = state();
    }

    static ProfileDraft of(ClientState.Profile p) {
        return new ProfileDraft(p.name(), p.name(), p.provider(), p.model(), p.baseUrl(), p.hasKey(),
                p.reasoningEffort());
    }

    /** Same settings under a new name; it keeps the original's saved key. */
    ProfileDraft copy(String newName) {
        ProfileDraft d = new ProfileDraft(from, newName, provider, model, savedUrl, hasKey, reasoningEffort);
        d.baseUrl = baseUrl;
        d.typedKey = typedKey;
        d.clearKey = clearKey;
        d.added = true;
        return d;
    }

    /** Every editable field; a typed key only counts as typed, so the text itself is never kept here. */
    String state() {
        return String.join("\n", String.valueOf(from), name, provider, String.valueOf(model), baseUrl,
                String.valueOf(reasoningEffort), String.valueOf(!typedKey.isEmpty()), String.valueOf(clearKey));
    }

    /** New, copied, or changed since the editor opened. */
    boolean edited() {
        return from == null || added || !state().equals(initial);
    }

    boolean isOpenAi() {
        return "openai".equals(provider);
    }

    /** Whether the saved key still applies: it is kept only for the URL it was saved with. */
    boolean keepsSavedKey() {
        return hasKey && !clearKey && ProfileEdits.sameUrl(baseUrl, savedUrl);
    }

    /** True when the server will have a key for this profile after saving. */
    boolean willHaveKey() {
        return !typedKey.isBlank() || keepsSavedKey();
    }

    ProfileEdits.Edit toEdit() {
        String key = clearKey && typedKey.isBlank() ? "" : typedKey.isBlank() ? null : typedKey.strip();
        return new ProfileEdits.Edit(from, name.strip(), provider, model.strip(), isOpenAi() ? baseUrl.strip() : null,
                isOpenAi() ? key : null, isOpenAi() ? reasoningEffort : null);
    }
}
