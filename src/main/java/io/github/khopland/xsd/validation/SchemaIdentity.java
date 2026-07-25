package io.github.khopland.xsd.validation;

import java.util.Objects;

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
    public SchemaIdentity {
        targetNamespace = Objects.requireNonNullElse(targetNamespace, "");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
