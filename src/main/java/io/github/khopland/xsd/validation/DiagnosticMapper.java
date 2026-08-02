package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.element;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.issue;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import io.github.khopland.xsd.validation.ValidationObservation.SeenElement;
import org.jspecify.annotations.Nullable;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class DiagnosticMapper {
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
        @Nullable DiagnosticIssueBuilder xml =
                XmlDiagnosticMapper.map(diagnostic, schema, choices);
        if (xml != null) {
            return xml;
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
            ContentDiagnosticMapper.ExpectedElements expected =
                    ContentDiagnosticMapper.expectedElements(diagnostic.arguments(), 1);
            List<QName> expectedPreview = expected.preview().isEmpty()
                    ? ContentDiagnosticMapper.preview(match.remainingElements())
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
        @Nullable DiagnosticIssueBuilder content =
                ContentDiagnosticMapper.map(diagnostic, choices);
        if (content != null) {
            return content;
        }

        String key = diagnostic.key();
        if (diagnostic.severity() == ValidationSeverity.FATAL) {
            return issue(
                    diagnostic,
                    "MALFORMED_XML",
                    "XML parsing stopped before the document ended.");
        }
        if (key.startsWith("cvc-complex-type.2.4")) {
            return issue(
                    diagnostic,
                    "UNEXPECTED_ELEMENT",
                    "Element " + element(diagnostic.actualElement())
                            + " is not permitted at this position.");
        }
        return issue(
                diagnostic,
                "SCHEMA_VALIDATION_ERROR",
                "XML does not satisfy schema constraint '" + key + "'.");
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
            ContentDiagnosticMapper.ExpectedElements expected =
                    ContentDiagnosticMapper.expectedElements(diagnostic.arguments(), 1);
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

    private static String renderElements(List<QName> elements) {
        return ContentDiagnosticMapper.preview(elements).stream()
                .map(DiagnosticMappingSupport::element)
                .reduce((left, right) -> left + " then " + right)
                .orElse("the alternative branch");
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

}
