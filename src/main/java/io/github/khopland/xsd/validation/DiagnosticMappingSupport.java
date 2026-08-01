package io.github.khopland.xsd.validation;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.xml.namespace.QName;
import org.jspecify.annotations.Nullable;

/** Shared safe rendering and issue assembly for diagnostic-family mappers. */
final class DiagnosticMappingSupport {
    private static final Pattern SAFE_NAME = Pattern.compile("[\\p{Alnum}_.:-]{1,100}");

    private DiagnosticMappingSupport() {
    }

    static boolean isSafeName(String value) {
        return SAFE_NAME.matcher(value).matches();
    }

    static Optional<String> safeNameArgument(
            @Nullable Object[] arguments,
            int index) {
        if (arguments.length <= index || arguments[index] == null) {
            return Optional.empty();
        }
        String candidate = Objects.requireNonNull(arguments[index]).toString();
        return isSafeName(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    static String element(@Nullable QName name) {
        return name == null ? "the current element" : "<" + name.getLocalPart() + ">";
    }

    static DiagnosticIssueBuilder issue(
            RawDiagnostic diagnostic,
            String code,
            String message) {
        return issue(diagnostic, code, message, List.of());
    }

    static DiagnosticIssueBuilder issue(
            RawDiagnostic diagnostic,
            String code,
            String message,
            List<QName> expectedElements) {
        return issue(diagnostic, code, message, expectedElements, null);
    }

    static DiagnosticIssueBuilder issue(
            RawDiagnostic diagnostic,
            String code,
            String message,
            List<QName> expectedElements,
            @Nullable QName actualAttribute) {
        return new DiagnosticIssueBuilder(
                diagnostic.severity(),
                code,
                message,
                diagnostic.path(),
                diagnostic.line(),
                diagnostic.column(),
                diagnostic.actualElement(),
                actualAttribute,
                null,
                expectedElements,
                new ArrayList<>(List.of(diagnostic.key())));
    }
}
