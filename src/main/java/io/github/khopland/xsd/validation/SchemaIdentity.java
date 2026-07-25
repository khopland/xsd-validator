package io.github.khopland.xsd.validation;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Stable identity of the compiled root schema.
 *
 * @param targetNamespace root schema target namespace, or an empty string
 * @param fingerprint SHA-256 fingerprint of the root schema and resolved dependencies
 */
public record SchemaIdentity(String targetNamespace, String fingerprint) {
    /**
     * Normalizes a missing target namespace to an empty string.
     */
    public SchemaIdentity(@Nullable String targetNamespace, String fingerprint) {
        this.targetNamespace = Objects.requireNonNullElse(targetNamespace, "");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
