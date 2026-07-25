package io.github.khopland.xsd.validation.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class ChoiceIndex {
    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    private final List<Choice> choices;

    private ChoiceIndex(List<Choice> choices) {
        this.choices = List.copyOf(choices);
    }

    static ChoiceIndex from(Document document) {
        List<Choice> choices = new ArrayList<>();
        var declarations = document.getElementsByTagNameNS(XSD_NAMESPACE, "element");
        for (int index = 0; index < declarations.getLength(); index++) {
            Element declaration = (Element) declarations.item(index);
            String parentName = declaration.getAttribute("name");
            directChild(declaration, "complexType")
                    .ifPresent(complexType -> findChoices(parentName, complexType, choices));
        }
        return new ChoiceIndex(choices);
    }

    Optional<Match> match(
            String parentLocalName,
            String actualLocalName,
            List<DocumentPathTracker.SeenElement> previousSiblings) {
        for (Choice choice : choices) {
            if (!choice.parentLocalName().equals(parentLocalName)) {
                continue;
            }

            int attemptedBranch = branchContaining(choice, actualLocalName);
            if (attemptedBranch < 0) {
                continue;
            }

            for (int siblingIndex = 0; siblingIndex < previousSiblings.size(); siblingIndex++) {
                DocumentPathTracker.SeenElement sibling = previousSiblings.get(siblingIndex);
                int selectedBranch = branchContaining(choice, sibling.name().getLocalPart());
                if (selectedBranch >= 0 && selectedBranch != attemptedBranch) {
                    return Optional.of(new Match(
                            sibling,
                            choice.branches().get(selectedBranch),
                            choice.branches().get(attemptedBranch)));
                }
            }
        }
        return Optional.empty();
    }

    Optional<IncompleteMatch> incomplete(
            String parentLocalName,
            List<DocumentPathTracker.SeenElement> previousSiblings) {
        for (Choice choice : choices) {
            if (!choice.parentLocalName().equals(parentLocalName)) {
                continue;
            }
            for (DocumentPathTracker.SeenElement sibling : previousSiblings) {
                int selectedBranch = branchContaining(choice, sibling.name().getLocalPart());
                if (selectedBranch < 0) {
                    continue;
                }
                List<String> remaining =
                        remaining(choice.branches().get(selectedBranch), previousSiblings);
                if (!remaining.isEmpty()) {
                    return Optional.of(new IncompleteMatch(sibling, remaining));
                }
            }
        }
        return Optional.empty();
    }

    private static int branchContaining(Choice choice, String elementName) {
        for (int index = 0; index < choice.branches().size(); index++) {
            if (choice.branches().get(index).contains(elementName)) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> remaining(
            List<String> branch,
            List<DocumentPathTracker.SeenElement> siblings) {
        List<String> remaining = new ArrayList<>(branch);
        for (DocumentPathTracker.SeenElement sibling : siblings) {
            remaining.remove(sibling.name().getLocalPart());
        }
        return List.copyOf(remaining);
    }

    private static void findChoices(String parentName, Element node, List<Choice> choices) {
        for (Element child : directChildren(node)) {
            if (isXsd(child, "element")) {
                continue;
            }
            if (isXsd(child, "choice")) {
                List<List<String>> branches = directChildren(child).stream()
                        .map(ChoiceIndex::branchElements)
                        .filter(branch -> !branch.isEmpty())
                        .toList();
                if (branches.size() > 1) {
                    choices.add(new Choice(parentName, branches));
                }
            }
            findChoices(parentName, child, choices);
        }
    }

    private static List<String> branchElements(Element branch) {
        if (isXsd(branch, "element")) {
            String name = branch.getAttribute("name");
            if (name.isEmpty()) {
                name = localPart(branch.getAttribute("ref"));
            }
            return name.isEmpty() ? List.of() : List.of(name);
        }

        List<String> names = new ArrayList<>();
        for (Element child : directChildren(branch)) {
            names.addAll(branchElements(child));
        }
        return List.copyOf(names);
    }

    private static Optional<Element> directChild(Element parent, String localName) {
        return directChildren(parent).stream()
                .filter(child -> isXsd(child, localName))
                .findFirst();
    }

    private static List<Element> directChildren(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    private static boolean isXsd(Element element, String localName) {
        return XSD_NAMESPACE.equals(element.getNamespaceURI())
                && localName.equals(element.getLocalName());
    }

    private static String localPart(String lexicalQName) {
        int separator = lexicalQName.indexOf(':');
        return separator < 0 ? lexicalQName : lexicalQName.substring(separator + 1);
    }

    private record Choice(String parentLocalName, List<List<String>> branches) {
    }

    record Match(
            DocumentPathTracker.SeenElement selectedBy,
            List<String> selectedBranch,
            List<String> attemptedBranch) {
    }

    record IncompleteMatch(
            DocumentPathTracker.SeenElement selectedBy,
            List<String> remainingElements) {
    }
}
