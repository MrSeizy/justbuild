package dev.justbuild.config;

import dev.justbuild.plan.PlanLimits;
import dev.justbuild.prompt.Guidance;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Contents of config/justbuild.json. Public fields so Gson can read and write them directly. */
public final class JustBuildConfig {
    public static final List<String> PROVIDERS = List.of("claude", "codex", "grok", "openai", "anthropic");
    public static final List<String> PLAYER_AI = List.of("require", "prefer", "off");
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1";
    public static final int DEFAULT_MAX_TOKENS = 8192;
    public static final int MAX_OUTPUT_TOKENS = 128000;

    public static boolean isApi(String provider) {
        return "openai".equals(provider) || "anthropic".equals(provider);
    }

    public static String defaultBaseUrl(String provider) {
        return "anthropic".equals(provider) ? DEFAULT_ANTHROPIC_BASE_URL : DEFAULT_BASE_URL;
    }

    /** Values an OpenAI-compatible profile may send as reasoning_effort; null leaves it to the endpoint. */
    public static final List<String> REASONING_EFFORTS = List.of("none", "low", "medium", "high");
    public static final int MAX_PROFILES = 20;
    public static final int MAX_NAME = 32;

    /** Named backend settings; normalize() replaces a missing or empty list with the starter profiles. */
    public List<ModelProfile> profiles;
    public String activeProfile;
    /** The profile the separate reviewer uses (a saved profile's name); empty means Same as builder. */
    public String reviewerProfile = "";
    /** Reference to the external unlocking key; ConfigStore alone encrypts/decrypts the on-disk API keys. */
    public String credentialKey;
    /** Where the CLIs are installed; shared by every profile that uses them. */
    public CliSettings claude = new CliSettings("~/.local/bin/claude");
    public CliSettings codex = new CliSettings("/opt/homebrew/bin/codex");
    public CliSettings grok = new CliSettings("");
    /** CLI planning: maximum silence between output; HTTP: request/body timeout. */
    public int timeoutSeconds = 600;
    public int maxOperations = 2000;
    public int maxBlocks = 250000;
    /** The largest maxBlocks this mod supports: relayed requests, previews, and quality checks are sized to it. */
    public static final int MAX_BLOCKS_LIMIT = 1_000_000;
    public int maxSize = 96;
    public int maxQuestions = 3;
    public int blocksPerTick = 2000;
    public int undoHistory = 10;
    /** Ask before an undo, since it also resets anything the player changed at the spots it restores. */
    public boolean confirmUndo = true;
    /** New AI builds above this threshold preview; 0 previews each, -1 places directly. Edits and saved plans always preview. */
    /** Every new build previews first unless a server owner raises this (blocks) or sets -1 (place directly). */
    public int previewAbove = 0;
    /** quick, detailed, or masterpiece (prompt depth and review rounds). */
    public String detail = "detailed";
    /** auto (the AI picks) or one of the named styles in Guidance.Style. */
    public String style = "auto";
    /** Draw a random creative direction for each new build. */
    public boolean creativeDirection = true;
    /** At detail level detailed or masterpiece, let the AI select references before it plans (at most one extra AI call). */
    public boolean guides = true;
    /**
     * Server: whose AI plans players' builds. prefer uses a player's own AI (in their game) when it is ready, else this
     * server's active profile; require refuses players without one; off always uses this server's profile.
     */
    public String playerAi = "require";
    /** Game: plan builds on servers with this computer's profiles when the server allows it. */
    public boolean useMyAi = true;
    /** Game only: hold each normalized AI response for local approval before sending it to a multiplayer server. */
    public boolean reviewAiResponses = false;
    /**
     * Game: the servers (address as typed in the server list, lower case) where this computer's AI may plan builds.
     * Empty by default: a server only gets your AI after you turn on "Use my AI here" while playing on it.
     */
    public List<String> myAiServers = new ArrayList<>();

    public static final class CliSettings {
        public String command;

        public CliSettings() {
        }

        CliSettings(String command) {
            this.command = command;
        }
    }

    /** One switchable backend: a provider and a model, plus an endpoint and key for HTTP APIs. */
    public static final class ModelProfile {
        public String name;
        public String provider;
        public String model = "";
        /** HTTP APIs only; null for the CLIs, so Gson leaves it out of the file. */
        public String baseUrl;
        /** Plaintext only in memory. ConfigStore writes encryptedApiKey instead. */
        public String apiKey;
        /** OpenAI-compatible only: sent as reasoning_effort when set (one of REASONING_EFFORTS). */
        public String reasoningEffort;
        /** Anthropic Messages API maximum output tokens. */
        public Integer maxTokens;

        public ModelProfile() {
        }

        public ModelProfile(String name, String provider, String model) {
            this.name = name;
            this.provider = provider;
            this.model = model;
            if (isApi()) {
                baseUrl = defaultBaseUrl(provider);
                apiKey = "";
            }
            if (isAnthropic()) maxTokens = DEFAULT_MAX_TOKENS;
        }

        public boolean isOpenAi() {
            return "openai".equals(provider);
        }

        public boolean isAnthropic() { return "anthropic".equals(provider); }

        public boolean isApi() { return JustBuildConfig.isApi(provider); }

        public int outputTokens() { return maxTokens == null ? DEFAULT_MAX_TOKENS : maxTokens; }

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public ModelProfile copy() {
            ModelProfile p = new ModelProfile();
            p.name = name;
            p.provider = provider;
            p.model = model;
            p.baseUrl = baseUrl;
            p.apiKey = apiKey;
            p.reasoningEffort = reasoningEffort;
            p.maxTokens = maxTokens;
            return p;
        }

        /** The effort in canonical form, or null for blank or unknown text. */
        public static String reasoningEffort(String text) {
            String effort = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
            return REASONING_EFFORTS.contains(effort) ? effort : null;
        }
    }

    /** Defaults with the starter profiles. */
    public static JustBuildConfig defaults() {
        return new JustBuildConfig().normalize();
    }

    public Guidance.DetailLevel detailLevel() {
        return Guidance.DetailLevel.parse(detail).orElse(Guidance.DetailLevel.DETAILED);
    }

    public Guidance.Style styleChoice() {
        return Guidance.Style.parse(style).orElse(Guidance.Style.AUTO);
    }

    public PlanLimits limits() {
        return new PlanLimits(maxOperations, maxBlocks, maxSize);
    }

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    /** The active profile; normalize() guarantees there is one. */
    public ModelProfile active() {
        return profile(activeProfile).orElse(profiles.get(0));
    }

    public Optional<ModelProfile> profile(String name) {
        if (name == null || profiles == null) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return profiles.stream().filter(p -> p.name.equalsIgnoreCase(wanted)).findFirst();
    }

    public List<String> profileNames() {
        return profiles.stream().map(p -> p.name).toList();
    }

    /** Makes the named profile active; false if there is none by that name. */
    public boolean activate(String name) {
        Optional<ModelProfile> p = profile(name);
        p.ifPresent(found -> activeProfile = found.name);
        return p.isPresent();
    }

    /** The separate reviewer's own profile, or empty for Same as builder. */
    public Optional<ModelProfile> reviewer() {
        return reviewerProfile == null || reviewerProfile.isBlank() ? Optional.empty() : profile(reviewerProfile);
    }

    /** True for a profile the reviewer can't select: "same" always means Same as builder. */
    public static boolean reservedReviewerName(String name) {
        return name != null && "same".equalsIgnoreCase(name.strip());
    }

    /** Profile names the reviewer can use (a profile named "same" can never be chosen over the keyword). */
    public List<String> reviewerChoices() {
        return profiles.stream().map(p -> p.name).filter(n -> !reservedReviewerName(n)).toList();
    }

    /** Sets the reviewer: "same" (any case) clears it, a profile name sets its canonical name; false changes nothing. */
    public boolean setReviewer(String input) {
        if (input == null) return false;
        if (reservedReviewerName(input)) {
            reviewerProfile = "";
            return true;
        }
        Optional<ModelProfile> found = profile(input);
        if (found.isEmpty()) return false;
        reviewerProfile = found.get().name;
        return true;
    }

    /** The reviewer after the current one in the local cycle: Same, each eligible profile in order, then Same again. */
    public String nextReviewer() {
        List<String> names = reviewerChoices();
        int next = names.indexOf(reviewerProfile == null ? "" : reviewerProfile) + 1;
        return next < names.size() ? names.get(next) : "";
    }

    /** Model of the active profile; empty means the CLI's default. */
    public String model() {
        return active().model;
    }

    public void setModel(String model) {
        active().model = model;
    }

    /** Replaces every profile (already validated by ProfileEdits) and the active one. */
    public void replaceProfiles(List<ModelProfile> replacement, String active) {
        profiles = new ArrayList<>(replacement.stream().map(ModelProfile::copy).toList());
        activeProfile = active;
        normalizeProfiles();
    }

    /** Replaces missing or out-of-range values (hand edits, older files) with defaults. */
    JustBuildConfig normalize() {
        JustBuildConfig d = new JustBuildConfig();
        claude = normalize(claude, d.claude);
        codex = normalize(codex, d.codex);
        grok = normalize(grok, d.grok);
        normalizeProfiles();
        timeoutSeconds = timeoutSeconds >= 10 ? timeoutSeconds : d.timeoutSeconds;
        maxOperations = positive(maxOperations, d.maxOperations);
        maxBlocks = Math.min(positive(maxBlocks, d.maxBlocks), MAX_BLOCKS_LIMIT);
        maxSize = positive(maxSize, d.maxSize);
        maxQuestions = maxQuestions >= 0 ? maxQuestions : d.maxQuestions;
        blocksPerTick = positive(blocksPerTick, d.blocksPerTick);
        undoHistory = positive(undoHistory, d.undoHistory);
        previewAbove = previewAbove >= -1 ? previewAbove : d.previewAbove;
        detail = detail != null && Guidance.DetailLevel.parse(detail).isPresent() ? detail.trim().toLowerCase() : d.detail;
        style = style != null && Guidance.Style.parse(style).isPresent() ? style.trim().toLowerCase() : d.style;
        myAiServers = myAiServers == null ? new ArrayList<>() : new ArrayList<>(myAiServers.stream()
                .filter(s -> s != null && !s.isBlank()).map(s -> s.strip().toLowerCase(Locale.ROOT)).distinct().toList());
        String ai = playerAi == null ? "" : playerAi.strip().toLowerCase(Locale.ROOT);
        playerAi = PLAYER_AI.contains(ai) ? ai : d.playerAi;
        return this;
    }

    private void normalizeProfiles() {
        if (profiles == null || profiles.isEmpty()) {
            profiles = starters();
        }

        List<ModelProfile> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ModelProfile p : profiles) {
            if (p == null) {
                continue;
            }
            if (clean.size() == MAX_PROFILES) {
                break;
            }
            ModelProfile q = p.copy();
            q.provider = q.provider != null && PROVIDERS.contains(q.provider.trim().toLowerCase(Locale.ROOT))
                    ? q.provider.trim().toLowerCase(Locale.ROOT) : "claude";
            String name = q.name == null ? "" : q.name.strip();
            q.name = unique(name.isEmpty() ? "Profile " + (clean.size() + 1) : clip(name, MAX_NAME), seen);
            q.model = q.model == null ? "" : q.model.strip();
            if (q.isApi()) {
                q.baseUrl = q.baseUrl == null || q.baseUrl.isBlank() ? defaultBaseUrl(q.provider) : q.baseUrl.strip();
                q.apiKey = q.apiKey == null ? "" : q.apiKey.strip();
                q.reasoningEffort = q.isOpenAi() ? ModelProfile.reasoningEffort(q.reasoningEffort) : null;
            } else {
                q.baseUrl = null;
                q.apiKey = null;
                q.reasoningEffort = null;
            }
            q.maxTokens = q.isAnthropic() ? (q.outputTokens() > 0 && q.outputTokens() <= MAX_OUTPUT_TOKENS
                    ? q.outputTokens() : DEFAULT_MAX_TOKENS) : null;
            clean.add(q);
        }
        profiles = clean.isEmpty() ? starters() : clean;
        activeProfile = profile(activeProfile).orElse(profiles.get(0)).name;
        reviewerProfile = reviewerProfile == null ? "" : profile(reviewerProfile).map(p -> p.name).orElse("");
    }

    /** One starter profile per backend. */
    private static List<ModelProfile> starters() {
        List<ModelProfile> list = new ArrayList<>();
        list.add(new ModelProfile("Claude", "claude", ""));
        list.add(new ModelProfile("Codex", "codex", ""));
        list.add(new ModelProfile("OpenAI-compatible", "openai", ""));
        list.add(new ModelProfile("Grok Build", "grok", ""));
        list.add(new ModelProfile("Anthropic-compatible", "anthropic", ""));
        return list;
    }

    private static String unique(String name, Set<String> seen) {
        String candidate = name;
        for (int n = 2; !seen.add(candidate.toLowerCase(Locale.ROOT)); n++) {
            String suffix = " " + n;
            candidate = clip(name, MAX_NAME - suffix.length()).strip() + suffix;
        }
        return candidate;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static CliSettings normalize(CliSettings value, CliSettings fallback) {
        if (value == null) {
            return fallback;
        }
        if (value.command == null || value.command.isBlank()) {
            value.command = fallback.command;
        }
        return value;
    }

    private static int positive(int value, int fallback) {
        return value >= 1 ? value : fallback;
    }
}
