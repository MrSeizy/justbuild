package dev.justbuild.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JustBuildConfigTest {
    @Test
    void theReviewerDefaultsToTheBuildersProfile() {
        JustBuildConfig config = JustBuildConfig.defaults();
        assertEquals("", config.reviewerProfile);
        assertTrue(config.reviewer().isEmpty());
    }

    @Test
    void theReviewerResolvesCaseInsensitivelyAndClearsWhenItsProfileIsGone() {
        JustBuildConfig config = JustBuildConfig.defaults();
        config.reviewerProfile = "codex";
        config.normalize();
        assertEquals("Codex", config.reviewerProfile);
        assertEquals("Codex", config.reviewer().orElseThrow().name);
        config.reviewerProfile = "Deleted";
        config.normalize();
        assertEquals("", config.reviewerProfile);
        config.reviewerProfile = null;
        config.normalize();
        assertEquals("", config.reviewerProfile);
    }

    @Test
    void olderConfigFilesLoadAsSameAsBuilder() {
        JustBuildConfig loaded = new com.google.gson.Gson().fromJson("{\"detail\":\"detailed\"}", JustBuildConfig.class).normalize();
        assertEquals("", loaded.reviewerProfile);
    }
}
