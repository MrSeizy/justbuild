package dev.justbuild.provider;

/** CLI model identifiers are single values, never options or shell syntax. HTTP model names stay endpoint-defined. */
public final class CliModel {
    private CliModel() { }

    public static boolean valid(String model) {
        return model == null || model.isBlank() || model.strip().matches("[A-Za-z0-9/@][A-Za-z0-9._:/@+\\[\\]-]{0,199}");
    }

    static void require(String model) throws ProviderException {
        if (!valid(model)) throw new ProviderException("Invalid CLI model name. Choose a model identifier from Models or use the CLI default.");
    }
}
