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
    void replacingProfilesKeepsTheReviewerWhileItsProfileRemains() {
        JustBuildConfig config = JustBuildConfig.defaults();
        config.reviewerProfile = "Codex";
        config.replaceProfiles(config.profiles, "Claude");
        assertEquals("Codex", config.reviewerProfile);
    }

    @Test
    void theReviewerFollowsARenameThroughReplaceProfiles() throws Exception {
        JustBuildConfig config = JustBuildConfig.defaults();
        config.reviewerProfile = "Codex";
        var request = new ProfileEdits.Request(java.util.List.of(
                new ProfileEdits.Edit("Claude", "Claude", "claude", "", null, null),
                new ProfileEdits.Edit("Codex", "Codex fast", "codex", "", null, null)), "Claude");
        var profiles = ProfileEdits.apply(config.profiles, request);
        config.reviewerProfile = ProfileEdits.renamed(request, config.reviewerProfile);
        config.replaceProfiles(profiles, request.active());
        assertEquals("Codex fast", config.reviewerProfile);
        assertEquals("Codex fast", config.reviewer().orElseThrow().name);
    }

    @Test
    void replacingProfilesClearsAReviewerWhoseProfileWasRemoved() {
        JustBuildConfig config = JustBuildConfig.defaults();
        config.reviewerProfile = "Codex";
        config.replaceProfiles(config.profiles.stream().filter(p -> !p.name.equals("Codex")).toList(), "Claude");
        assertEquals("", config.reviewerProfile);
        assertTrue(config.reviewer().isEmpty());
    }

    @Test
    void setReviewerTakesTheKeywordOrAProfileName() {
        JustBuildConfig config = JustBuildConfig.defaults();
        config.profiles.add(new JustBuildConfig.ModelProfile("Claude fast", "claude", "haiku"));
        assertTrue(config.setReviewer("codex"));
        assertEquals("Codex", config.reviewerProfile);
        for (String same : new String[] {"same", "SAME", " Same "}) {
            config.reviewerProfile = "Codex";
            assertTrue(config.setReviewer(same));
            assertEquals("", config.reviewerProfile);
        }
        assertTrue(config.setReviewer("claude fast"));
        assertEquals("Claude fast", config.reviewerProfile);
        assertFalse(config.setReviewer("Nope"));
        assertFalse(config.setReviewer(null));
        assertEquals("Claude fast", config.reviewerProfile);
    }

    @Test
    void nextReviewerCyclesThroughSameAndEveryProfile() {
        JustBuildConfig config = JustBuildConfig.defaults();
        java.util.List<String> names = config.profileNames();
        assertEquals(names.get(0), config.nextReviewer());
        for (int i = 0; i < names.size(); i++) {
            config.reviewerProfile = config.nextReviewer();
            assertEquals(names.get(i), config.reviewerProfile);
        }
        config.reviewerProfile = config.nextReviewer();
        assertEquals("", config.reviewerProfile);
    }

    @Test
    void aProfileNamedSameIsNeverAReviewerChoice() {
        JustBuildConfig config = JustBuildConfig.defaults();
        config.profiles.add(1, new JustBuildConfig.ModelProfile("Same", "claude", ""));
        assertFalse(config.reviewerChoices().contains("Same"));
        assertEquals(config.profileNames().size() - 1, config.reviewerChoices().size());
        config.reviewerProfile = config.profileNames().get(0);
        assertEquals(config.profileNames().get(2), config.nextReviewer());
        assertTrue(config.setReviewer("same"));
        assertEquals("", config.reviewerProfile);
    }

    @Test
    void olderConfigFilesLoadAsSameAsBuilder() {
        JustBuildConfig loaded = new com.google.gson.Gson().fromJson("{\"detail\":\"detailed\"}", JustBuildConfig.class).normalize();
        assertEquals("", loaded.reviewerProfile);
    }
}
