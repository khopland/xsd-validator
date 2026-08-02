package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.element;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.issue;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import io.github.khopland.xsd.validation.ValidationObservation.SeenElement;
import java.util.List;
import java.util.Optional;
import javax.xml.namespace.QName;
import org.jspecify.annotations.Nullable;

/** Maps content-model diagnostics that can be enriched with choice metadata. */
final class ChoiceDiagnosticMapper {
    private ChoiceDiagnosticMapper() {
    }

    static @Nullable DiagnosticIssueBuilder map(
            RawDiagnostic diagnostic,
            ChoiceIndex choices) {
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
        if (incompleteChoice.isEmpty()) {
            return null;
        }

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
