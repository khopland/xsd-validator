package io.github.khopland.xsd.validation;

import java.util.Objects;

/**
 * Stable identity of the compiled root schema.
 */
public record SchemaIdentity(String targetNamespace, String fingerprint) {
    public SchemaIdentity {
        targetNamespace = Objects.requireNonNullElse(targetNamespace, "");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
