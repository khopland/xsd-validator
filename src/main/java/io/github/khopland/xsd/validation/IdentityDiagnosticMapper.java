package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.element;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.issue;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.safeNameArgument;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import org.jspecify.annotations.Nullable;

/** Maps Xerces identity-constraint diagnostics to stable library issues. */
final class IdentityDiagnosticMapper {
    private IdentityDiagnosticMapper() {
    }

    static @Nullable DiagnosticIssueBuilder map(RawDiagnostic diagnostic) {
        return switch (diagnostic.key()) {
            case "DuplicateKey" -> identityIssue(
                    diagnostic,
                    "DUPLICATE_KEY",
                    2,
                    element(diagnostic.actualElement())
                            + " duplicates a key required to be unique by identity constraint");
            case "DuplicateUnique" -> identityIssue(
                    diagnostic,
                    "DUPLICATE_UNIQUE",
                    2,
                    element(diagnostic.actualElement())
                            + " duplicates a value required to be unique by identity constraint");
            case "KeyNotFound" -> identityIssue(
                    diagnostic,
                    "KEY_REFERENCE_NOT_FOUND",
                    0,
                    element(diagnostic.actualElement())
                            + " contains a reference not found by identity constraint");
            case "AbsentKeyValue", "KeyNotEnoughValues" -> identityIssue(
                    diagnostic,
                    "KEY_VALUE_MISSING",
                    1,
                    element(diagnostic.actualElement())
                            + " is missing a value required by identity constraint");
            default -> null;
        };
    }

    private static DiagnosticIssueBuilder identityIssue(
            RawDiagnostic diagnostic,
            String code,
            int constraintArgument,
            String messagePrefix) {
        @Nullable String constraintName =
                safeNameArgument(diagnostic.arguments(), constraintArgument).orElse(null);
        String message = constraintName == null
                ? messagePrefix + "."
                : messagePrefix + " '" + constraintName + "'.";
        DiagnosticIssueBuilder mapped = issue(diagnostic, code, message);
        mapped.constraintName(constraintName);
        return mapped;
    }
}
