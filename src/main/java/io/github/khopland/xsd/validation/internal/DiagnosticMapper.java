package io.github.khopland.xsd.validation.internal;

import io.github.khopland.xsd.validation.SchemaIdentity;
import io.github.khopland.xsd.validation.ValidationIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.xml.namespace.QName;

final class DiagnosticMapper {
    private static final Pattern SAFE_TYPE_NAME = Pattern.compile("[\\p{Alnum}_.:-]{1,100}");

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

        if (diagnostic.key().startsWith("cvc-datatype-valid")) {
            String typeName = safeArgument(diagnostic.arguments(), 1).orElse("the declared type");
            String message = "Element " + element(diagnostic.actualElement())
                    + " does not satisfy type '" + typeName + "'.";
            return issue(diagnostic, "INVALID_VALUE", message);
        }

        if (diagnostic.severity() == io.github.khopland.xsd.validation.ValidationSeverity.FATAL) {
            return issue(diagnostic, "MALFORMED_XML", "XML parsing stopped before the document ended.");
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
        return new IssueBuilder(
                diagnostic.severity(),
                code,
                message,
                diagnostic.path(),
                diagnostic.line(),
                diagnostic.column(),
                diagnostic.actualElement(),
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
        private final List<String> schemaCodes;

        private IssueBuilder(
                io.github.khopland.xsd.validation.ValidationSeverity severity,
                String code,
                String message,
                String path,
                int line,
                int column,
                QName actualElement,
                List<String> schemaCodes) {
            this.severity = severity;
            this.code = code;
            this.message = message;
            this.path = path;
            this.line = line;
            this.column = column;
            this.actualElement = actualElement;
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
                    List.of(),
                    schemaCodes);
        }
    }
}
