package dev.justbuild.relay;

/**
 * What the player's game remembers once it has sent a response (the client's RelaySeen applies it). Kept here, with no
 * game classes, so the decision can be unit tested.
 *
 * @param question a question the AI asked
 * @param planKey the key of a plan the AI returned
 * @param repairPair {@code critiqueKey + ">" + planKey} when a repair carrying that critique returned the plan
 * @param critiqueKey the key of a critique the AI returned
 */
public record RelayRecording(String question, String planKey, String repairPair, String critiqueKey) {
    private static final RelayRecording NOTHING = new RelayRecording(null, null, null, null);

    /** A question yields only the question; a plan its key (and the repair pair); a critique its key; all else nothing. */
    public static RelayRecording of(String repairOf, RelayOutput output) {
        if (output == null) return NOTHING;
        if (output.question() != null) return new RelayRecording(output.question(), null, null, null);
        if (output.planKey() != null) {
            boolean pair = repairOf != null && !repairOf.isBlank() && !output.planKey().isBlank();
            return new RelayRecording(null, output.planKey(), pair ? repairOf + ">" + output.planKey() : null, null);
        }
        if (output.critiqueKey() != null) return new RelayRecording(null, null, null, output.critiqueKey());
        return NOTHING;
    }
}
