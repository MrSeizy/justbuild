package dev.justbuild.provider;

public record ProcessResult(int exitCode, String stdout, String stderr) {
}
