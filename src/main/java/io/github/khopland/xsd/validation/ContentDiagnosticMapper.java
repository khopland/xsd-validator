package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.element;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.isSafeName;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.issue;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import io.github.khopland.xsd.validation.ValidationObservation.SeenElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.xml.namespace.QName;
import org.jspecify.annotations.Nullable;

/** Maps content-model diagnostics after choice-specific enrichment. */
final class ContentDiagnosticMapper {
    private static final int MAX_EXPECTED_ELEMENTS = 5;
    private static final Pattern QUALIFIED_ELEMENT =
            Pattern.compile("^\"([^\"]*)\":([\\p{Alnum}_.-]+)$");

    private ContentDiagnosticMapper() {
    }

    static @Nullable DiagnosticIssueBuilder map(
            RawDiagnostic diagnostic,
            ChoiceIndex choices) {
        String key = diagnostic.key();
        return switch (key) {
            case "cvc-complex-type.2.4.e", "cvc-complex-type.2.4.f" -> {
                int maximum = integerArgument(
                        diagnostic.arguments(),
                        key.equals("cvc-complex-type.2.4.e") ? 2 : 1);
                ExpectedElements expected = key.equals("cvc-complex-type.2.4.e")
                        ? expectedElements(diagnostic.arguments(), 1)
                        : ExpectedElements.EMPTY;
                String message = "Element " + element(diagnostic.actualElement())
                        + " exceeds its maximum occurrence"
                        + (maximum > 0 ? " of " + maximum : "")
                        + (expected.preview().isEmpty()
                        ? "."
                        : "; expected " + expected.description() + " instead.");
                yield issue(
                        diagnostic,
                        "MAX_OCCURS_EXCEEDED",
                        message,
                        expected.preview());
            }
            case "cvc-complex-type.2.4.g", "cvc-complex-type.2.4.h" -> {
                ExpectedElements expected = expectedElements(diagnostic.arguments(), 1);
                int required = integerArgument(
                        diagnostic.arguments(),
                        key.equals("cvc-complex-type.2.4.h") ? 3 : -1);
                yield issue(
                        diagnostic,
                        "MIN_OCCURS_NOT_MET",
                        "Element " + element(diagnostic.actualElement())
                                + " occurs too early; add "
                                + count(required) + expected.description() + " first.",
                        expected.preview());
            }
            case "cvc-complex-type.2.4.i", "cvc-complex-type.2.4.j" -> {
                ExpectedElements expected = expectedElements(diagnostic.arguments(), 1);
                int required = integerArgument(
                        diagnostic.arguments(),
                        key.equals("cvc-complex-type.2.4.j") ? 3 : -1);
                yield issue(
                        diagnostic,
                        "MIN_OCCURS_NOT_MET",
                        "Element " + element(diagnostic.actualElement())
                                + " is incomplete; add "
                                + count(required) + expected.description()
                                + " before it closes.",
                        expected.preview());
            }
            case "cvc-complex-type.2.4.a" -> {
                ExpectedElements expected = expectedElements(diagnostic.arguments(), 1);
                yield issue(
                        diagnostic,
                        "UNEXPECTED_ELEMENT",
                        "Element " + element(diagnostic.actualElement())
                                + " is not permitted here; expected "
                                + expected.description() + ".",
                        expected.preview());
            }
            case "cvc-complex-type.2.4.b" -> {
                ExpectedElements expected = expectedElements(diagnostic.arguments(), 1);
                yield issue(
                        diagnostic,
                        "MISSING_ELEMENT",
                        "Element " + element(diagnostic.actualElement())
                                + " is incomplete; add " + expected.description()
                                + " before it closes.",
                        expected.preview());
            }
            case "cvc-complex-type.2.4.d" -> {
                long previousOccurrences = diagnostic.previousSiblings().stream()
                        .map(SeenElement::name)
                        .filter(name -> name.equals(diagnostic.actualElement()))
                        .count();
                int maximumOccurrences = choices.maximumOccurrences(
                        diagnostic.parentType(),
                        diagnostic.actualElement());
                boolean duplicate = maximumOccurrences > 0
                        && previousOccurrences >= maximumOccurrences;
                yield duplicate
                        ? issue(
                        diagnostic,
                        "DUPLICATE_ELEMENT",
                        "Element " + element(diagnostic.actualElement())
                        + " already occurred and cannot occur again here.")
                        : issue(
                        diagnostic,
                        "UNEXPECTED_ELEMENT",
                        "Element " + element(diagnostic.actualElement())
                        + " is not permitted at this position.");
            }
            default -> null;
        };
    }

    static ExpectedElements expectedElements(@Nullable Object[] arguments, int index) {
        if (arguments.length <= index || arguments[index] == null) {
            return ExpectedElements.EMPTY;
        }
        String value = Objects.requireNonNull(arguments[index]).toString().trim();
        if (value.startsWith("{") && value.endsWith("}")) {
            value = value.substring(1, value.length() - 1);
        }

        List<QName> elements = splitTerms(value).stream()
                .map(ContentDiagnosticMapper::parseElement)
                .flatMap(Optional::stream)
                .toList();
        return new ExpectedElements(preview(elements), elements.size());
    }

    static List<QName> preview(List<QName> elements) {
        return elements.stream().limit(MAX_EXPECTED_ELEMENTS).toList();
    }

    private static int integerArgument(@Nullable Object[] arguments, int index) {
        if (index < 0 || arguments.length <= index || arguments[index] == null) {
            return -1;
        }
        try {
            return Integer.parseInt(Objects.requireNonNull(arguments[index]).toString());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String count(int count) {
        return count > 1 ? count + " more occurrences of " : "";
    }

    private static List<String> splitTerms(String value) {
        List<String> terms = new ArrayList<>();
        boolean quoted = false;
        int nested = 0;
        int start = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (!quoted && (character == '(' || character == '[')) {
                nested++;
            } else if (!quoted && (character == ')' || character == ']')) {
                nested--;
            } else if (!quoted && nested == 0 && character == ',') {
                terms.add(value.substring(start, index).trim());
                start = index + 1;
            }
        }
        if (start < value.length()) {
            terms.add(value.substring(start).trim());
        }
        return terms;
    }

    private static Optional<QName> parseElement(String term) {
        var qualified = QUALIFIED_ELEMENT.matcher(term);
        if (qualified.matches()) {
            return Optional.of(new QName(qualified.group(1), qualified.group(2)));
        }
        return isSafeName(term) && !term.contains(":")
                ? Optional.of(new QName(term))
                : Optional.empty();
    }

    record ExpectedElements(List<QName> preview, int total) {
        private static final ExpectedElements EMPTY = new ExpectedElements(List.of(), 0);

        private String description() {
            if (preview.isEmpty()) {
                return "schema-required content";
            }
            String rendered = preview.stream()
                    .map(DiagnosticMappingSupport::element)
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow();
            if (total > preview.size()) {
                rendered += ", and " + (total - preview.size()) + " more";
            }
            return preview.size() == 1 && total == 1 ? rendered : "one of " + rendered;
        }
    }
}
