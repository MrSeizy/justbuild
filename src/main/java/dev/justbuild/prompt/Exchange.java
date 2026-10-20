package dev.justbuild.prompt;

/** A clarifying question the AI asked and the player's answer. */
public record Exchange(String question, String answer) {
}
