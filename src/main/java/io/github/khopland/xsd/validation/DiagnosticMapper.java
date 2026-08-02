package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.element;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.issue;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import org.jspecify.annotations.Nullable;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

        @Nullable DiagnosticIssueBuilder choice =
                ChoiceDiagnosticMapper.map(diagnostic, choices);
        if (choice != null) {
            return choice;
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

}
