package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.element;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.issue;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.safeNameArgument;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import java.util.List;
import java.util.Objects;
import javax.xml.namespace.QName;
import org.jspecify.annotations.Nullable;

/** Maps datatype and facet diagnostics without retaining submitted values. */
final class ValueDiagnosticMapper {
    private static final int MAX_ENUM_VALUES = 5;
    private static final int MAX_ENUM_VALUE_LENGTH = 40;

    private ValueDiagnosticMapper() {
    }

    static @Nullable DiagnosticIssueBuilder map(
            RawDiagnostic diagnostic,
            @Nullable QName actualAttribute) {
        String key = diagnostic.key();
        if (key.startsWith("cvc-datatype-valid")) {
            String typeName = safeNameArgument(diagnostic.arguments(), 1)
                    .orElse("the declared type");
            String message = subject(diagnostic.actualElement(), actualAttribute)
                    + " does not satisfy type '" + typeName + "'.";
            return issue(
                    diagnostic,
                    "INVALID_VALUE",
                    message,
                    List.of(),
                    actualAttribute);
        }
        String subject = subject(diagnostic.actualElement(), actualAttribute);
        return switch (key) {
            case "cvc-enumeration-valid" -> {
                AllowedValues allowed = allowedValues(diagnostic.arguments(), 1);
                yield issue(
                        diagnostic,
                        "ENUMERATION_VIOLATION",
                        subject + " must be " + allowed.description() + ".",
                        List.of(),
                        actualAttribute);
            }
            case "cvc-pattern-valid" -> issue(
                    diagnostic,
                    "PATTERN_MISMATCH",
                    subject + " does not match its schema pattern.",
                    List.of(),
                    actualAttribute);
            case "cvc-length-valid" -> lengthIssue(
                    diagnostic, actualAttribute, subject, "");
            case "cvc-minLength-valid" -> lengthIssue(
                    diagnostic, actualAttribute, subject, "at least ");
            case "cvc-maxLength-valid" -> lengthIssue(
                    diagnostic, actualAttribute, subject, "at most ");
            case "cvc-minInclusive-valid" -> boundIssue(
                    diagnostic, actualAttribute, subject, "at least", "MINIMUM_VIOLATION");
            case "cvc-minExclusive-valid" -> boundIssue(
                    diagnostic, actualAttribute, subject, "greater than", "MINIMUM_VIOLATION");
            case "cvc-maxInclusive-valid" -> boundIssue(
                    diagnostic, actualAttribute, subject, "at most", "MAXIMUM_VIOLATION");
            case "cvc-maxExclusive-valid" -> boundIssue(
                    diagnostic, actualAttribute, subject, "less than", "MAXIMUM_VIOLATION");
            case "cvc-totalDigits-valid" -> issue(
                    diagnostic,
                    "TOTAL_DIGITS_EXCEEDED",
                    subject + " may contain at most "
                            + schemaArgument(diagnostic.arguments(), 2)
                            + " total digits.",
                    List.of(),
                    actualAttribute);
            case "cvc-fractionDigits-valid" -> issue(
                    diagnostic,
                    "FRACTION_DIGITS_EXCEEDED",
                    subject + " may contain at most "
                            + schemaArgument(diagnostic.arguments(), 2)
                            + " fractional digits.",
                    List.of(),
                    actualAttribute);
            default -> null;
        };
    }

    static boolean isValueDiagnostic(String key) {
        if (key.startsWith("cvc-datatype-valid")) {
            return true;
        }
        return switch (key) {
            case "cvc-enumeration-valid",
                 "cvc-pattern-valid",
                 "cvc-length-valid",
                 "cvc-minLength-valid",
                 "cvc-maxLength-valid",
                 "cvc-minInclusive-valid",
                 "cvc-minExclusive-valid",
                 "cvc-maxInclusive-valid",
                 "cvc-maxExclusive-valid",
                 "cvc-totalDigits-valid",
                 "cvc-fractionDigits-valid" -> true;
            default -> false;
        };
    }

    private static DiagnosticIssueBuilder lengthIssue(
            RawDiagnostic diagnostic,
            @Nullable QName actualAttribute,
            String subject,
            String comparison) {
        return issue(
                diagnostic,
                "LENGTH_VIOLATION",
                subject + " must have length " + comparison
                        + schemaArgument(diagnostic.arguments(), 2)
                        + "; its submitted length is "
                        + schemaArgument(diagnostic.arguments(), 1) + ".",
                List.of(),
                actualAttribute);
    }

    private static DiagnosticIssueBuilder boundIssue(
            RawDiagnostic diagnostic,
            @Nullable QName actualAttribute,
            String subject,
            String comparison,
            String code) {
        return issue(
                diagnostic,
                code,
                subject + " must be " + comparison + " "
                        + schemaArgument(diagnostic.arguments(), 1) + ".",
                List.of(),
                actualAttribute);
    }

    private static String schemaArgument(@Nullable Object[] arguments, int index) {
        if (arguments.length <= index || arguments[index] == null) {
            return "the schema limit";
        }
        String value = Objects.requireNonNull(arguments[index]).toString();
        if (value.chars().anyMatch(Character::isISOControl)) {
            return "the schema limit";
        }
        return value.length() <= 80 ? value : value.substring(0, 80) + "…";
    }

    private static AllowedValues allowedValues(@Nullable Object[] arguments, int index) {
        if (arguments.length <= index || arguments[index] == null) {
            return AllowedValues.EMPTY;
        }
        String value = Objects.requireNonNull(arguments[index]).toString();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        // Xerces flattens enum facets into one string; use XSModel when
        // comma-containing enumeration values need lossless structured previews.
        List<String> values = value.isEmpty()
                ? List.of()
                : List.of(value.split(", ", -1));
        return new AllowedValues(
                values.stream()
                        .limit(MAX_ENUM_VALUES)
                        .map(ValueDiagnosticMapper::boundedEnumValue)
                        .toList(),
                values.size());
    }

    private static String boundedEnumValue(String value) {
        String safe = value.chars().anyMatch(Character::isISOControl)
                ? "…"
                : value;
        return safe.length() <= MAX_ENUM_VALUE_LENGTH
                ? safe
                : safe.substring(0, MAX_ENUM_VALUE_LENGTH) + "…";
    }

    private static String attribute(@Nullable QName name) {
        return name == null ? "the current attribute" : "@" + name.getLocalPart();
    }

    private static String subject(@Nullable QName element, @Nullable QName attribute) {
        return attribute == null
                ? "Element " + element(element)
                : "Attribute " + attribute(attribute) + " on " + element(element);
    }

    private record AllowedValues(List<String> preview, int total) {
        private static final AllowedValues EMPTY = new AllowedValues(List.of(), 0);

        private String description() {
            if (preview.isEmpty()) {
                return "one of the schema's allowed values";
            }
            String rendered = preview.stream()
                    .map(value -> "‘" + value + "’")
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow();
            if (total > preview.size()) {
                rendered += ", and " + (total - preview.size()) + " more";
            }
            return "one of " + rendered;
        }
    }
}
