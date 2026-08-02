package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.element;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.isSafeName;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.issue;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import io.github.khopland.xsd.validation.ValidationObservation.SeenElement;
import org.jspecify.annotations.Nullable;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

final class DiagnosticMapper {
    private static final int MAX_EXPECTED_ELEMENTS = 5;
    private static final Pattern QUALIFIED_ELEMENT =
            Pattern.compile("^\"([^\"]*)\":([\\p{Alnum}_.-]+)$");
    private DiagnosticMapper() {
    }

    static List<ValidationIssue> map(
            List<RawDiagnostic> diagnostics,
            SchemaIdentity schema,
            ChoiceIndex choices) {
        List<DiagnosticIssueBuilder> issues = new ArrayList<>();
        for (int index = 0; index < diagnostics.size(); index++) {
            RawDiagnostic diagnostic = diagnostics.get(index);
            try {
                if (index + 1 < diagnostics.size()
                        && isValueCompanion(diagnostic, diagnostics.get(index + 1))) {
                    RawDiagnostic companion = diagnostics.get(++index);
                    QName attribute = "cvc-attribute.3".equals(companion.key())
                            ? AttributeDiagnosticMapper.attributeName(companion)
                            : null;
                    DiagnosticIssueBuilder issue =
                            mapOne(diagnostic, schema, choices, attribute);
                    issue.addSchemaCode(companion.key());
                    issues.add(issue);
                } else if (index + 1 < diagnostics.size()
                        && isFixedAttributeCompanion(diagnostic, diagnostics.get(index + 1))) {
                    DiagnosticIssueBuilder issue = mapOne(
                            diagnostic,
                            schema,
                            choices,
                            AttributeDiagnosticMapper.attributeName(diagnostic));
                    issue.addSchemaCode(diagnostics.get(++index).key());
                    issues.add(issue);
                } else {
                    issues.add(mapOne(
                            diagnostic,
                            schema,
                            choices,
                            AttributeDiagnosticMapper.attributeName(diagnostic)));
                }
            } catch (RuntimeException exception) {
                issues.add(issue(
                        diagnostic,
                        "SCHEMA_VALIDATION_ERROR",
                        "XML does not satisfy schema constraint '" + diagnostic.key() + "'."));
            }
        }
        return issues.stream().map(DiagnosticIssueBuilder::build).toList();
    }

    private static DiagnosticIssueBuilder mapOne(
            RawDiagnostic diagnostic,
            SchemaIdentity schema,
            ChoiceIndex choices,
            @Nullable QName actualAttribute) {
        if ("cvc-elt.1.a".equals(diagnostic.key())
                && diagnostic.actualElement() != null
                && choices.hasRootLocalName(diagnostic.actualElement().getLocalPart())
                && !namespace(diagnostic.actualElement()).equals(schema.targetNamespace())) {
            String actualNamespace = namespace(diagnostic.actualElement());
            String message = "Root element " + element(diagnostic.actualElement())
                    + " uses namespace '" + actualNamespace + "'; the schema expects '"
                    + schema.targetNamespace() + "'.";
            return issue(diagnostic, "ROOT_NAMESPACE_MISMATCH", message);
        }

        Optional<ChoiceIndex.Match> choice = choiceMatch(diagnostic, choices);
        if (choice.isPresent()) {
            ChoiceIndex.Match match = choice.get();
            String message = element(diagnostic.actualElement())
                    + " cannot occur here: "
                    + element(match.selectedBy())
                    + location(lineOf(diagnostic.previousSiblings(), match.selectedBy()))
                    + " already selected a mutually exclusive choice.";
            List<QName> remaining = match.remainingElements();
            if (!remaining.isEmpty()) {
                message += " Complete that branch with " + renderElements(remaining)
                        + ", or remove it before using "
                        + renderElements(match.attemptedBranch()) + ".";
            }
            return issue(diagnostic, "CHOICE_ALREADY_SELECTED", message);
        }

        Optional<ChoiceIndex.IncompleteMatch> incompleteChoice =
                incompleteChoice(diagnostic, choices);
        if (incompleteChoice.isPresent()) {
            ChoiceIndex.IncompleteMatch match = incompleteChoice.get();
            ExpectedElements expected = expectedElements(diagnostic.arguments(), 1);
            List<QName> expectedPreview = expected.preview().isEmpty()
                    ? match.remainingElements().stream()
                    .limit(MAX_EXPECTED_ELEMENTS)
                    .toList()
                    : expected.preview();
            String message = element(match.selectedBy())
                    + location(lineOf(diagnostic.children(), match.selectedBy()))
                    + " selected a choice branch that is incomplete. Add "
                    + renderElements(match.remainingElements())
                    + " before " + element(diagnostic.actualElement()) + " closes.";
            return issue(
                    diagnostic,
                    "CHOICE_BRANCH_INCOMPLETE",
                    message,
                    expectedPreview);
        }

        @Nullable DiagnosticIssueBuilder identity = IdentityDiagnosticMapper.map(diagnostic);
        if (identity != null) {
            return identity;
        }
        @Nullable DiagnosticIssueBuilder instance = InstanceDiagnosticMapper.map(diagnostic);
        if (instance != null) {
            return instance;
        }
        @Nullable DiagnosticIssueBuilder attribute =
                AttributeDiagnosticMapper.map(diagnostic, actualAttribute);
        if (attribute != null) {
            return attribute;
        }
        @Nullable DiagnosticIssueBuilder value =
                ValueDiagnosticMapper.map(diagnostic, actualAttribute);
        if (value != null) {
            return value;
        }

        String key = diagnostic.key();
        return switch (key) {
            case "cvc-elt.1.a" -> issue(
                    diagnostic,
                    "UNDECLARED_ROOT",
                    "Root element " + element(diagnostic.actualElement())
                            + " is not declared by the compiled schema.");
            case "xml-processing-stopped" -> issue(
                    diagnostic,
                    "XML_PROCESSING_ERROR",
                    "XML validation could not process the supplied Source.");
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
            default -> {
                if (diagnostic.severity() == ValidationSeverity.FATAL) {
                    yield issue(
                            diagnostic,
                            "MALFORMED_XML",
                            "XML parsing stopped before the document ended.");
                }
                if (key.startsWith("cvc-complex-type.2.4")) {
                    yield issue(
                            diagnostic,
                            "UNEXPECTED_ELEMENT",
                            "Element " + element(diagnostic.actualElement())
                                    + " is not permitted at this position.");
                }
                yield issue(
                        diagnostic,
                        "SCHEMA_VALIDATION_ERROR",
                        "XML does not satisfy schema constraint '" + key + "'.");
            }
        };
    }

    private static Optional<ChoiceIndex.Match> choiceMatch(
            RawDiagnostic diagnostic,
            ChoiceIndex choices) {
        if (!(diagnostic.key().equals("cvc-complex-type.2.4.a")
                || diagnostic.key().equals("cvc-complex-type.2.4.d"))
                || diagnostic.parentElement() == null
                || diagnostic.actualElement() == null) {
            return Optional.empty();
        }
        return choices.match(
                diagnostic.parentType(),
                diagnostic.parentElement(),
                diagnostic.actualElement(),
                names(diagnostic.previousSiblings()));
    }

    private static Optional<ChoiceIndex.IncompleteMatch> incompleteChoice(
            RawDiagnostic diagnostic,
            ChoiceIndex choices) {
        if (!"cvc-complex-type.2.4.b".equals(diagnostic.key())
                || diagnostic.actualElement() == null) {
            return Optional.empty();
        }
        return choices.incomplete(
                diagnostic.actualType(),
                diagnostic.actualElement(),
                names(diagnostic.children())).filter(match -> {
            ExpectedElements expected = expectedElements(diagnostic.arguments(), 1);
            return expected.preview().isEmpty()
                    || expected.preview().stream()
                    .anyMatch(match.remainingElements()::contains);
        });
    }

    private static boolean isValueCompanion(
            RawDiagnostic specific,
            RawDiagnostic companion) {
        return ValueDiagnosticMapper.isValueDiagnostic(specific.key())
                && ("cvc-type.3.1.3".equals(companion.key())
                || "cvc-attribute.3".equals(companion.key()))
                && specific.path().equals(companion.path())
                && specific.line() == companion.line();
    }

    private static boolean isFixedAttributeCompanion(
            RawDiagnostic specific,
            RawDiagnostic companion) {
        return "cvc-attribute.4".equals(specific.key())
                && "cvc-complex-type.3.1".equals(companion.key())
                && specific.path().equals(companion.path())
                && specific.line() == companion.line()
                && Objects.equals(
                AttributeDiagnosticMapper.attributeName(specific),
                AttributeDiagnosticMapper.attributeName(companion));
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

    private static ExpectedElements expectedElements(@Nullable Object[] arguments, int index) {
        if (arguments.length <= index || arguments[index] == null) {
            return ExpectedElements.EMPTY;
        }
        String value = Objects.requireNonNull(arguments[index]).toString().trim();
        if (value.startsWith("{") && value.endsWith("}")) {
            value = value.substring(1, value.length() - 1);
        }

        List<QName> elements = splitTerms(value).stream()
                .map(DiagnosticMapper::parseElement)
                .flatMap(Optional::stream)
                .toList();
        return new ExpectedElements(
                elements.stream().limit(MAX_EXPECTED_ELEMENTS).toList(),
                elements.size());
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

    private static String renderElements(List<QName> elements) {
        return elements.stream()
                .limit(MAX_EXPECTED_ELEMENTS)
                .map(DiagnosticMappingSupport::element)
                .reduce((left, right) -> left + " then " + right)
                .orElse("the alternative branch");
    }

    private static String namespace(@Nullable QName name) {
        return name == null ? "" : name.getNamespaceURI();
    }

    private static String location(int line) {
        return line > 0 ? " at line " + line : "";
    }

    private static List<QName> names(List<SeenElement> elements) {
        return elements.stream().map(SeenElement::name).toList();
    }

    private static int lineOf(List<SeenElement> elements, QName name) {
        return elements.stream()
                .filter(element -> element.name().equals(name))
                .mapToInt(SeenElement::line)
                .findFirst()
                .orElse(-1);
    }

    private record ExpectedElements(List<QName> preview, int total) {
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
