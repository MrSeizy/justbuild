package dev.justbuild.provider;

/** A backend failed; the message is shown to the player. */
public class ProviderException extends Exception {
    public ProviderException(String message) {
        super(message);
    }
}
