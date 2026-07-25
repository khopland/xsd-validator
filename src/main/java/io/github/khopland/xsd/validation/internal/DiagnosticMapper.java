package io.github.khopland.xsd.validation.internal;

import io.github.khopland.xsd.validation.SchemaIdentity;
import io.github.khopland.xsd.validation.ValidationIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.xml.namespace.QName;

final class DiagnosticMapper {
    private static final int MAX_EXPECTED_ELEMENTS = 5;
    private static final int MAX_ENUM_VALUES = 5;
    private static final int MAX_ENUM_VALUE_LENGTH = 40;
    private static final Pattern SAFE_TYPE_NAME = Pattern.compile("[\\p{Alnum}_.:-]{1,100}");
    private static final Pattern QUALIFIED_ELEMENT =
            Pattern.compile("^\"([^\"]*)\":([\\p{Alnum}_.-]+)$");

    private DiagnosticMapper() {
    }

    static List<ValidationIssue> map(
            List<RawDiagnostic> diagnostics,
            SchemaIdentity schema,
            ChoiceIndex choices) {
        List<IssueBuilder> issues = new ArrayList<>();
        for (int index = 0; index < diagnostics.size(); index++) {
            RawDiagnostic diagnostic = diagnostics.get(index);
            if (index + 1 < diagnostics.size()
                    && isValueCompanion(diagnostic, diagnostics.get(index + 1))) {
                RawDiagnostic companion = diagnostics.get(++index);
                QName attribute = "cvc-attribute.3".equals(companion.key())
                        ? attributeName(companion)
                        : null;
                IssueBuilder issue = mapOne(diagnostic, schema, choices, attribute);
                issue.schemaCodes.add(companion.key());
                issues.add(issue);
            } else {
                issues.add(mapOne(
                        diagnostic,
                        schema,
                        choices,
                        attributeName(diagnostic)));
            }
        }
        return issues.stream().map(IssueBuilder::build).toList();
    }

    private static IssueBuilder mapOne(
            RawDiagnostic diagnostic,
            SchemaIdentity schema,
            ChoiceIndex choices,
            QName actualAttribute) {
        if ("cvc-elt.1.a".equals(diagnostic.key())
                && !namespace(diagnostic.actualElement()).equals(schema.targetNamespace())) {
            String actualNamespace = namespace(diagnostic.actualElement());
            String message = "Root element " + element(diagnostic.actualElement())
                    + " uses namespace '" + actualNamespace + "'; the schema expects '"
                    + schema.targetNamespace() + "'.";
            return issue(diagnostic, "ROOT_NAMESPACE_MISMATCH", message);
        }

        if ("cvc-elt.1.a".equals(diagnostic.key())) {
            return issue(
                    diagnostic,
                    "UNDECLARED_ROOT",
                    "Root element " + element(diagnostic.actualElement())
                            + " is not declared by the compiled schema.");
        }

        if ("cvc-complex-type.4".equals(diagnostic.key())
                || "cvc-complex-type.4_ns".equals(diagnostic.key())) {
            return issue(
                    diagnostic,
                    "REQUIRED_ATTRIBUTE_MISSING",
                    "Required attribute " + attribute(actualAttribute)
                            + " is missing from " + element(diagnostic.actualElement()) + ".",
                    List.of(),
                    actualAttribute);
        }

        if ("cvc-complex-type.3.2.1".equals(diagnostic.key())
                || "cvc-complex-type.3.2.2".equals(diagnostic.key())) {
            return issue(
                    diagnostic,
                    "ATTRIBUTE_NOT_ALLOWED",
                    "Attribute " + attribute(actualAttribute)
                            + " is not allowed on " + element(diagnostic.actualElement()) + ".",
                    List.of(),
                    actualAttribute);
        }

        if ("cvc-attribute.3".equals(diagnostic.key())) {
            String typeName = safeArgument(diagnostic.arguments(), 3)
                    .orElse("the declared type");
            return issue(
                    diagnostic,
                    "INVALID_ATTRIBUTE_VALUE",
                    "Attribute " + attribute(actualAttribute)
                            + " on " + element(diagnostic.actualElement())
                            + " does not satisfy type '" + typeName + "'.",
                    List.of(),
                    actualAttribute);
        }

        Optional<ChoiceIndex.Match> choice = choiceMatch(diagnostic, choices);
        if (choice.isPresent()) {
            ChoiceIndex.Match match = choice.get();
            String message = element(diagnostic.actualElement())
                    + " cannot occur here: "
                    + element(match.selectedBy().name())
                    + location(match.selectedBy().line())
                    + " already selected a mutually exclusive choice.";
            List<String> remaining = remainingElements(
                    match.selectedBranch(),
                    diagnostic.previousSiblings());
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
                            .map(name -> new QName(
                                    namespace(diagnostic.actualElement()),
                                    name))
                            .limit(MAX_EXPECTED_ELEMENTS)
                            .toList()
                    : expected.preview();
            String message = element(match.selectedBy().name())
                    + location(match.selectedBy().line())
                    + " selected a choice branch that is incomplete. Add "
                    + renderElements(match.remainingElements())
                    + " before " + element(diagnostic.actualElement()) + " closes.";
            return issue(
                    diagnostic,
                    "CHOICE_BRANCH_INCOMPLETE",
                    message,
                    expectedPreview);
        }

        if (isFacetDiagnostic(diagnostic.key())) {
            return facetIssue(diagnostic, actualAttribute);
        }

        if (diagnostic.key().startsWith("cvc-datatype-valid")) {
            String typeName = safeArgument(diagnostic.arguments(), 1).orElse("the declared type");
            String message = subject(diagnostic.actualElement(), actualAttribute)
                    + " does not satisfy type '" + typeName + "'.";
            return issue(diagnostic, "INVALID_VALUE", message, List.of(), actualAttribute);
        }

        if (diagnostic.severity() == io.github.khopland.xsd.validation.ValidationSeverity.FATAL) {
            return issue(diagnostic, "MALFORMED_XML", "XML parsing stopped before the document ended.");
        }

        if ("cvc-complex-type.2.4.e".equals(diagnostic.key())
                || "cvc-complex-type.2.4.f".equals(diagnostic.key())) {
            int maximum = integerArgument(
                    diagnostic.arguments(),
                    "cvc-complex-type.2.4.e".equals(diagnostic.key()) ? 2 : 1);
            ExpectedElements expected = "cvc-complex-type.2.4.e".equals(diagnostic.key())
                    ? expectedElements(diagnostic.arguments(), 1)
                    : ExpectedElements.EMPTY;
            String message = "Element " + element(diagnostic.actualElement())
                    + " exceeds its maximum occurrence"
                    + (maximum > 0 ? " of " + maximum : "")
                    + (expected.preview().isEmpty()
                            ? "."
                            : "; expected " + expected.description() + " instead.");
            return issue(
                    diagnostic,
                    "MAX_OCCURS_EXCEEDED",
                    message,
                    expected.preview());
        }

        if ("cvc-complex-type.2.4.g".equals(diagnostic.key())
                || "cvc-complex-type.2.4.h".equals(diagnostic.key())) {
            ExpectedElements expected = expectedElements(diagnostic.arguments(), 1);
            int required = integerArgument(
                    diagnostic.arguments(),
                    "cvc-complex-type.2.4.h".equals(diagnostic.key()) ? 3 : -1);
            return issue(
                    diagnostic,
                    "MIN_OCCURS_NOT_MET",
                    "Element " + element(diagnostic.actualElement())
                            + " occurs too early; add "
                            + count(required) + expected.description() + " first.",
                    expected.preview());
        }

        if ("cvc-complex-type.2.4.i".equals(diagnostic.key())
                || "cvc-complex-type.2.4.j".equals(diagnostic.key())) {
            ExpectedElements expected = expectedElements(diagnostic.arguments(), 1);
            int required = integerArgument(
                    diagnostic.arguments(),
                    "cvc-complex-type.2.4.j".equals(diagnostic.key()) ? 3 : -1);
            return issue(
                    diagnostic,
                    "MIN_OCCURS_NOT_MET",
                    "Element " + element(diagnostic.actualElement())
                            + " is incomplete; add "
                            + count(required) + expected.description()
                            + " before it closes.",
                    expected.preview());
        }

        if ("cvc-complex-type.2.4.a".equals(diagnostic.key())) {
            ExpectedElements expected = expectedElements(diagnostic.arguments(), 1);
            return issue(
                    diagnostic,
                    "UNEXPECTED_ELEMENT",
                    "Element " + element(diagnostic.actualElement())
                            + " is not permitted here; expected "
                            + expected.description() + ".",
                    expected.preview());
        }

        if ("cvc-complex-type.2.4.b".equals(diagnostic.key())) {
            ExpectedElements expected = expectedElements(diagnostic.arguments(), 1);
            return issue(
                    diagnostic,
                    "MISSING_ELEMENT",
                    "Element " + element(diagnostic.actualElement())
                            + " is incomplete; add " + expected.description()
                            + " before it closes.",
                    expected.preview());
        }

        if ("cvc-complex-type.2.4.d".equals(diagnostic.key())
                && diagnostic.actualElement() != null
                && diagnostic.previousSiblings().stream()
                        .map(DocumentPathTracker.SeenElement::name)
                        .anyMatch(diagnostic.actualElement()::equals)) {
            return issue(
                    diagnostic,
                    "DUPLICATE_ELEMENT",
                    "Element " + element(diagnostic.actualElement())
                            + " already occurred and cannot occur again here.");
        }

        if (diagnostic.key().startsWith("cvc-complex-type.2.4")) {
            return issue(
                    diagnostic,
                    "UNEXPECTED_ELEMENT",
                    "Element " + element(diagnostic.actualElement())
                            + " is not permitted at this position.");
        }

        return issue(
                diagnostic,
                "SCHEMA_VALIDATION_ERROR",
                "XML does not satisfy schema constraint '" + diagnostic.key() + "'.");
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
                diagnostic.parentElement().getLocalPart(),
                diagnostic.actualElement().getLocalPart(),
                diagnostic.previousSiblings());
    }

    private static Optional<ChoiceIndex.IncompleteMatch> incompleteChoice(
            RawDiagnostic diagnostic,
            ChoiceIndex choices) {
        if (!"cvc-complex-type.2.4.b".equals(diagnostic.key())
                || diagnostic.actualElement() == null) {
            return Optional.empty();
        }
        return choices.incomplete(
                diagnostic.actualElement().getLocalPart(),
                diagnostic.children()).filter(match -> {
                    ExpectedElements expected = expectedElements(diagnostic.arguments(), 1);
                    return expected.preview().isEmpty()
                            || expected.preview().stream()
                                    .map(QName::getLocalPart)
                                    .anyMatch(match.remainingElements()::contains);
                });
    }

    private static IssueBuilder facetIssue(
            RawDiagnostic diagnostic,
            QName actualAttribute) {
        String subject = subject(diagnostic.actualElement(), actualAttribute);
        return switch (diagnostic.key()) {
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
            case "cvc-length-valid" -> issue(
                    diagnostic,
                    "LENGTH_VIOLATION",
                    subject + " must have length "
                            + schemaArgument(diagnostic.arguments(), 2)
                            + "; its submitted length is "
                            + schemaArgument(diagnostic.arguments(), 1) + ".",
                    List.of(),
                    actualAttribute);
            case "cvc-minLength-valid" -> issue(
                    diagnostic,
                    "LENGTH_VIOLATION",
                    subject + " must have length at least "
                            + schemaArgument(diagnostic.arguments(), 2)
                            + "; its submitted length is "
                            + schemaArgument(diagnostic.arguments(), 1) + ".",
                    List.of(),
                    actualAttribute);
            case "cvc-maxLength-valid" -> issue(
                    diagnostic,
                    "LENGTH_VIOLATION",
                    subject + " must have length at most "
                            + schemaArgument(diagnostic.arguments(), 2)
                            + "; its submitted length is "
                            + schemaArgument(diagnostic.arguments(), 1) + ".",
                    List.of(),
                    actualAttribute);
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
            default -> throw new IllegalArgumentException(
                    "Unsupported facet key: " + diagnostic.key());
        };
    }

    private static IssueBuilder boundIssue(
            RawDiagnostic diagnostic,
            QName actualAttribute,
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

    private static boolean isValueCompanion(
            RawDiagnostic specific,
            RawDiagnostic companion) {
        return isValueDiagnostic(specific.key())
                && ("cvc-type.3.1.3".equals(companion.key())
                        || "cvc-attribute.3".equals(companion.key()))
                && specific.path().equals(companion.path())
                && specific.line() == companion.line();
    }

    private static boolean isValueDiagnostic(String key) {
        return key.startsWith("cvc-datatype-valid") || isFacetDiagnostic(key);
    }

    private static boolean isFacetDiagnostic(String key) {
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

    private static boolean isAttributeDiagnostic(String key) {
        return key.startsWith("cvc-attribute.")
                || key.startsWith("cvc-complex-type.3.")
                || key.equals("cvc-complex-type.4")
                || key.equals("cvc-complex-type.4_ns");
    }

    private static QName attributeName(RawDiagnostic diagnostic) {
        if (!isAttributeDiagnostic(diagnostic.key())
                || diagnostic.arguments().length < 2
                || diagnostic.arguments()[1] == null) {
            return null;
        }
        String lexicalName = diagnostic.arguments()[1].toString();
        int separator = lexicalName.indexOf(':');
        String localName = separator < 0
                ? lexicalName
                : lexicalName.substring(separator + 1);
        if (!SAFE_TYPE_NAME.matcher(localName).matches()) {
            return null;
        }
        Optional<QName> present = diagnostic.attributes().stream()
                .filter(name -> name.getLocalPart().equals(localName))
                .findFirst();
        if (present.isPresent()) {
            return present.get();
        }
        if ("cvc-complex-type.4_ns".equals(diagnostic.key())
                && diagnostic.arguments().length > 2
                && diagnostic.arguments()[2] != null) {
            return new QName(diagnostic.arguments()[2].toString(), localName);
        }
        return new QName(localName);
    }

    private static Optional<String> safeArgument(Object[] arguments, int index) {
        if (arguments.length <= index || arguments[index] == null) {
            return Optional.empty();
        }
        String candidate = arguments[index].toString();
        return SAFE_TYPE_NAME.matcher(candidate).matches()
                ? Optional.of(candidate)
                : Optional.empty();
    }

    private static String schemaArgument(Object[] arguments, int index) {
        if (arguments.length <= index || arguments[index] == null) {
            return "the schema limit";
        }
        String value = arguments[index].toString();
        if (value.chars().anyMatch(Character::isISOControl)) {
            return "the schema limit";
        }
        return value.length() <= 80 ? value : value.substring(0, 80) + "…";
    }

    private static AllowedValues allowedValues(Object[] arguments, int index) {
        if (arguments.length <= index || arguments[index] == null) {
            return AllowedValues.EMPTY;
        }
        String value = arguments[index].toString();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        // ponytail: Xerces flattens enum facets into one string; use XSModel when
        // comma-containing enumeration values need lossless structured previews.
        List<String> values = value.isEmpty()
                ? List.of()
                : List.of(value.split(", ", -1));
        return new AllowedValues(
                values.stream()
                        .limit(MAX_ENUM_VALUES)
                        .map(DiagnosticMapper::boundedEnumValue)
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

    private static int integerArgument(Object[] arguments, int index) {
        if (index < 0 || arguments.length <= index || arguments[index] == null) {
            return 1;
        }
        try {
            return Integer.parseInt(arguments[index].toString());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String count(int count) {
        return count > 1 ? count + " more occurrences of " : "";
    }

    private static ExpectedElements expectedElements(Object[] arguments, int index) {
        if (arguments.length <= index || arguments[index] == null) {
            return ExpectedElements.EMPTY;
        }
        String value = arguments[index].toString().trim();
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
        return SAFE_TYPE_NAME.matcher(term).matches() && !term.contains(":")
                ? Optional.of(new QName(term))
                : Optional.empty();
    }

    private static List<String> remainingElements(
            List<String> selectedBranch,
            List<DocumentPathTracker.SeenElement> siblings) {
        List<String> remaining = new ArrayList<>(selectedBranch);
        for (DocumentPathTracker.SeenElement sibling : siblings) {
            remaining.remove(sibling.name().getLocalPart());
        }
        return List.copyOf(remaining);
    }

    private static String renderElements(List<String> elements) {
        return elements.stream().map(name -> "<" + name + ">").reduce((left, right) ->
                left + " then " + right).orElse("the alternative branch");
    }

    private static String namespace(QName name) {
        return name == null ? "" : name.getNamespaceURI();
    }

    private static String element(QName name) {
        return name == null ? "the current element" : "<" + name.getLocalPart() + ">";
    }

    private static String attribute(QName name) {
        return name == null ? "the current attribute" : "@" + name.getLocalPart();
    }

    private static String subject(QName element, QName attribute) {
        return attribute == null
                ? "Element " + element(element)
                : "Attribute " + attribute(attribute) + " on " + element(element);
    }

    private static String location(int line) {
        return line > 0 ? " at line " + line : "";
    }

    private static IssueBuilder issue(RawDiagnostic diagnostic, String code, String message) {
        return issue(diagnostic, code, message, List.of());
    }

    private static IssueBuilder issue(
            RawDiagnostic diagnostic,
            String code,
            String message,
            List<QName> expectedElements) {
        return issue(diagnostic, code, message, expectedElements, null);
    }

    private static IssueBuilder issue(
            RawDiagnostic diagnostic,
            String code,
            String message,
            List<QName> expectedElements,
            QName actualAttribute) {
        return new IssueBuilder(
                diagnostic.severity(),
                code,
                message,
                diagnostic.path(),
                diagnostic.line(),
                diagnostic.column(),
                diagnostic.actualElement(),
                actualAttribute,
                expectedElements,
                new ArrayList<>(List.of(diagnostic.key())));
    }

    private static final class IssueBuilder {
        private final io.github.khopland.xsd.validation.ValidationSeverity severity;
        private final String code;
        private final String message;
        private final String path;
        private final int line;
        private final int column;
        private final QName actualElement;
        private final QName actualAttribute;
        private final List<QName> expectedElements;
        private final List<String> schemaCodes;

        private IssueBuilder(
                io.github.khopland.xsd.validation.ValidationSeverity severity,
                String code,
                String message,
                String path,
                int line,
                int column,
                QName actualElement,
                QName actualAttribute,
                List<QName> expectedElements,
                List<String> schemaCodes) {
            this.severity = severity;
            this.code = code;
            this.message = message;
            this.path = path;
            this.line = line;
            this.column = column;
            this.actualElement = actualElement;
            this.actualAttribute = actualAttribute;
            this.expectedElements = expectedElements;
            this.schemaCodes = schemaCodes;
        }

        private ValidationIssue build() {
            return new ValidationIssue(
                    severity,
                    code,
                    message,
                    path,
                    line,
                    column,
                    actualElement,
                    actualAttribute,
                    expectedElements,
                    schemaCodes);
        }
    }

    private record ExpectedElements(List<QName> preview, int total) {
        private static final ExpectedElements EMPTY = new ExpectedElements(List.of(), 0);

        private String description() {
            if (preview.isEmpty()) {
                return "schema-required content";
            }
            String rendered = preview.stream()
                    .map(DiagnosticMapper::element)
                    .reduce((left, right) -> left + ", " + right)
                    .orElseThrow();
            if (total > preview.size()) {
                rendered += ", and " + (total - preview.size()) + " more";
            }
            return preview.size() == 1 && total == 1 ? rendered : "one of " + rendered;
        }
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
