package dev.justbuild.plan;

/** The AI's response or plan is unusable; the message is shown to the player. */
public class PlanException extends Exception {
    public PlanException(String message) {
        super(message);
    }
}
