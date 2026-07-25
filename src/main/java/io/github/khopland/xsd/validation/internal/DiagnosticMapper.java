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
        for (RawDiagnostic diagnostic : diagnostics) {
            if (isGenericTypeDuplicate(diagnostic, issues)) {
                issues.get(issues.size() - 1).schemaCodes.add(diagnostic.key());
                continue;
            }
            issues.add(mapOne(diagnostic, schema, choices));
        }
        return issues.stream().map(IssueBuilder::build).toList();
    }

    private static IssueBuilder mapOne(
            RawDiagnostic diagnostic,
            SchemaIdentity schema,
            ChoiceIndex choices) {
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

        if (diagnostic.key().startsWith("cvc-datatype-valid")) {
            String typeName = safeArgument(diagnostic.arguments(), 1).orElse("the declared type");
            String message = "Element " + element(diagnostic.actualElement())
                    + " does not satisfy type '" + typeName + "'.";
            return issue(diagnostic, "INVALID_VALUE", message);
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

    private static boolean isGenericTypeDuplicate(
            RawDiagnostic diagnostic,
            List<IssueBuilder> issues) {
        if (!"cvc-type.3.1.3".equals(diagnostic.key()) || issues.isEmpty()) {
            return false;
        }
        IssueBuilder previous = issues.get(issues.size() - 1);
        return previous.path.equals(diagnostic.path())
                && previous.line == diagnostic.line()
                && previous.schemaCodes.stream()
                        .anyMatch(code -> code.startsWith("cvc-datatype-valid"));
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
        return new IssueBuilder(
                diagnostic.severity(),
                code,
                message,
                diagnostic.path(),
                diagnostic.line(),
                diagnostic.column(),
                diagnostic.actualElement(),
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
                List<QName> expectedElements,
                List<String> schemaCodes) {
            this.severity = severity;
            this.code = code;
            this.message = message;
            this.path = path;
            this.line = line;
            this.column = column;
            this.actualElement = actualElement;
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
}
