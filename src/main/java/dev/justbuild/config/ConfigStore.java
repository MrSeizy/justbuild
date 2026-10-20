package dev.justbuild.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** Loads config/justbuild.json, migrating plaintext credentials to authenticated encryption on first read. */
public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;

    public ConfigStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public JustBuildConfig load() throws ConfigException {
        if (!Files.exists(file)) {
            JustBuildConfig defaults = JustBuildConfig.defaults();
            save(defaults);
            return defaults;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            boolean migrate = hasPlaintextFields(json);
            unlock(json);
            JustBuildConfig config = GSON.fromJson(json, JustBuildConfig.class).normalize();
            if (migrate) save(config);
            return config;
        } catch (RuntimeException e) {
            // Parser/type error messages can quote the value that failed; that value might be a credential.
            throw new ConfigException("Could not parse " + file.getFileName() + ". Check its JSON and field types; the file was not changed.");
        } catch (IOException e) {
            throw new ConfigException("Could not read " + file + ": " + e.getMessage());
        }
    }

    /** An independent deep copy, for work that must not see later changes (an AI request in flight). */
    public static JustBuildConfig copy(JustBuildConfig config) {
        return GSON.fromJson(GSON.toJson(config), JustBuildConfig.class);
    }

    public void save(JustBuildConfig config) throws ConfigException {
        Path temp = null;
        byte[] key = null;
        try {
            JustBuildConfig snapshot = copy(config).normalize();
            JsonObject json = GSON.toJsonTree(snapshot).getAsJsonObject();
            boolean hasKeys = snapshot.profiles.stream().anyMatch(JustBuildConfig.ModelProfile::hasKey);
            String reference = snapshot.credentialKey;
            if (hasKeys) {
                if (reference == null) {
                    var created = CredentialKeys.create();
                    reference = created.reference(); key = created.key();
                } else key = CredentialKeys.load(reference);
                json.addProperty("credentialKey", reference);
            }
            JsonArray profiles = json.getAsJsonArray("profiles");
            for (int i = 0; i < snapshot.profiles.size(); i++) {
                var profile = snapshot.profiles.get(i);
                JsonObject item = profiles.get(i).getAsJsonObject();
                item.remove("apiKey");
                if (profile.hasKey()) item.addProperty("encryptedApiKey",
                        CredentialCipher.encrypt(profile.apiKey, key, profile.name, profile.baseUrl));
            }
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            temp = PrivateFiles.configTemporary(dir, file.getFileName().toString());
            Files.writeString(temp, GSON.toJson(json) + "\n");
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            config.credentialKey = reference;
        } catch (IOException e) {
            throw new ConfigException("Could not write " + file + ": " + e.getMessage());
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    private static boolean hasPlaintextFields(JsonObject json) {
        if (json.get("profiles") instanceof JsonArray profiles) {
            for (var item : profiles) if (item instanceof JsonObject profile && profile.has("apiKey")) return true;
        }
        return false;
    }

    /** Decode before normalization so changing the name or endpoint by hand cannot redirect a saved key. */
    private static void unlock(JsonObject json) throws ConfigException {
        if (!(json.get("profiles") instanceof JsonArray profiles)) return;
        byte[] key = null;
        try {
            for (var item : profiles) {
                if (!(item instanceof JsonObject profile) || !profile.has("encryptedApiKey")) continue;
                if (profile.has("apiKey") && !profile.get("apiKey").isJsonNull()
                        && !profile.get("apiKey").getAsString().isBlank()) {
                    throw new ConfigException("A profile contains both apiKey and encryptedApiKey. Remove the encrypted "
                            + "entry before replacing a key by hand. The config was not changed.");
                }
                if (key == null) key = CredentialKeys.load(json.has("credentialKey") ? json.get("credentialKey").getAsString() : null);
                String secret = CredentialCipher.decrypt(profile.get("encryptedApiKey").getAsString(), key,
                        profile.get("name").getAsString(), profile.get("baseUrl").getAsString());
                profile.remove("encryptedApiKey");
                profile.addProperty("apiKey", secret);
            }
        } finally { if (key != null) Arrays.fill(key, (byte) 0); }
    }
}
