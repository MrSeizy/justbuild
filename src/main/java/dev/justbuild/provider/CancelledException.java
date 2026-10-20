package dev.justbuild.provider;

public class CancelledException extends ProviderException {
    public CancelledException() {
        super("Cancelled");
    }
}
