package dev.justbuild.client;

import java.util.Objects;

/** Follows external settings until edited; a different target starts an independent draft. */
final class TextDraft {
    private String target;
    private String saved;
    private String value;

    String receive(String target, String next) {
        if (!Objects.equals(this.target, target) || value == null || value.equals(saved)) value = next;
        this.target = target;
        saved = next;
        return value;
    }

    void edit(String value) { this.value = value; }
    void replace(String value) { this.value = value; this.saved = value; }
}
