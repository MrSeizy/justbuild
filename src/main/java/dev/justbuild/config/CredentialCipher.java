package dev.justbuild.config;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Versioned AES-256-GCM envelopes, authenticated against their profile name and endpoint. */
final class CredentialCipher {
    private static final String PREFIX = "aesgcm-v1:";
    private static final int MAX_ENVELOPE_LENGTH = 16_384;
    private static final int MAX_PLAINTEXT_BYTES = 12_000; // Includes room for the nonce, tag, prefix and base64 expansion.
    private static final SecureRandom RANDOM = new SecureRandom();
    private CredentialCipher() { }

    static String encrypt(String secret, byte[] key, String name, String endpoint) throws ConfigException {
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        byte[] plain = secret.getBytes(StandardCharsets.UTF_8);
        try {
            if (plain.length > MAX_PLAINTEXT_BYTES) throw new ConfigException(
                    "An API key exceeds the credential storage limit (12000 UTF-8 bytes). The config was not changed.");
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, name, endpoint);
            byte[] encrypted = cipher.doFinal(plain);
            byte[] packed = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw new ConfigException("Could not encrypt the API keys. The config was not changed.");
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    static String decrypt(String envelope, byte[] key, String name, String endpoint) throws ConfigException {
        try {
            if (!envelope.startsWith(PREFIX) || envelope.length() > MAX_ENVELOPE_LENGTH) throw new IllegalArgumentException();
            byte[] packed = Base64.getDecoder().decode(envelope.substring(PREFIX.length()));
            if (packed.length < 28) throw new IllegalArgumentException();
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, Arrays.copyOf(packed, 12), name, endpoint);
            byte[] plain = cipher.doFinal(packed, 12, packed.length - 12);
            try { return new String(plain, StandardCharsets.UTF_8); }
            finally { Arrays.fill(plain, (byte) 0); }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ConfigException("A saved API key could not be authenticated. Its encrypted value, profile name, "
                    + "or endpoint may have changed. Restore the config or remove that encryptedApiKey entry and "
                    + "enter the key again. The config was not changed.");
        }
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, String name, String endpoint)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD("justbuild-api-key-v1".getBytes(StandardCharsets.UTF_8));
        for (String value : new String[] {name, endpoint}) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            cipher.updateAAD(ByteBuffer.allocate(4).putInt(bytes.length).array());
            cipher.updateAAD(bytes);
        }
        return cipher;
    }
}
