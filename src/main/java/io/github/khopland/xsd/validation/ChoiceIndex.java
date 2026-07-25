package io.github.khopland.xsd.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.validation.Schema;
import org.apache.xerces.jaxp.validation.XSGrammarPoolContainer;
import org.apache.xerces.xni.grammars.Grammar;
import org.apache.xerces.xni.grammars.XMLGrammarDescription;
import org.apache.xerces.xni.grammars.XSGrammar;
import org.apache.xerces.xs.XSComplexTypeDefinition;
import org.apache.xerces.xs.XSConstants;
import org.apache.xerces.xs.XSElementDeclaration;
import org.apache.xerces.xs.XSModel;
import org.apache.xerces.xs.XSModelGroup;
import org.apache.xerces.xs.XSNamedMap;
import org.apache.xerces.xs.XSObject;
import org.apache.xerces.xs.XSParticle;
import org.apache.xerces.xs.XSTerm;

final class ChoiceIndex {
    private final List<Choice> choices;
    private final Set<String> rootLocalNames;

    private ChoiceIndex(List<Choice> choices, Set<String> rootLocalNames) {
        this.choices = List.copyOf(choices);
        this.rootLocalNames = Set.copyOf(rootLocalNames);
    }

    static ChoiceIndex from(Schema schema, String targetNamespace) {
        XSModel model = model(schema);
        List<Choice> choices = new ArrayList<>();
        Set<String> rootLocalNames = new LinkedHashSet<>();
        Set<XSElementDeclaration> visited =
                Collections.newSetFromMap(new IdentityHashMap<>());
        XSNamedMap elements = model.getComponents(XSConstants.ELEMENT_DECLARATION);
        for (int index = 0; index < elements.getLength(); index++) {
            XSElementDeclaration element = (XSElementDeclaration) elements.item(index);
            if (namespace(element).equals(targetNamespace)) {
                rootLocalNames.add(element.getName());
            }
            indexElement(element, choices, visited);
        }
        return new ChoiceIndex(choices, rootLocalNames);
    }

    boolean hasRootLocalName(String localName) {
        return rootLocalNames.contains(localName);
    }

    Optional<Match> match(
            QName parentName,
            QName actualName,
            List<DocumentPathTracker.SeenElement> previousSiblings) {
        for (Choice choice : choices) {
            if (!choice.parentName().equals(parentName)) {
                continue;
            }

            List<Branch> attemptedBranches =
                    branchesContaining(choice.branches(), actualName);
            if (attemptedBranches.isEmpty()) {
                continue;
            }

            List<Branch> candidates = compatibleBranches(choice, previousSiblings);
            if (candidates.isEmpty()
                    || candidates.stream().anyMatch(branch -> branch.contains(actualName))) {
                continue;
            }
            Branch selected = candidates.get(0);
            DocumentPathTracker.SeenElement selectedBy = previousSiblings.stream()
                    .filter(sibling -> selected.contains(sibling.name()))
                    .findFirst()
                    .orElseThrow();
            return Optional.of(new Match(
                    selectedBy,
                    remaining(selected, previousSiblings),
                    attemptedBranches.get(0).names()));
        }
        return Optional.empty();
    }

    Optional<IncompleteMatch> incomplete(
            QName parentName,
            List<DocumentPathTracker.SeenElement> previousSiblings) {
        for (Choice choice : choices) {
            if (!choice.parentName().equals(parentName)) {
                continue;
            }
            List<Branch> candidates = compatibleBranches(choice, previousSiblings);
            if (candidates.size() != 1) {
                continue;
            }
            Branch selected = candidates.get(0);
            List<QName> remaining = remaining(selected, previousSiblings);
            if (!remaining.isEmpty()) {
                DocumentPathTracker.SeenElement selectedBy = previousSiblings.stream()
                        .filter(sibling -> selected.contains(sibling.name()))
                        .findFirst()
                        .orElseThrow();
                return Optional.of(new IncompleteMatch(selectedBy, remaining));
            }
        }
        return Optional.empty();
    }

    private static XSModel model(Schema schema) {
        Grammar[] grammars = ((XSGrammarPoolContainer) schema)
                .getGrammarPool()
                .retrieveInitialGrammarSet(XMLGrammarDescription.XML_SCHEMA);
        XSGrammar[] schemaGrammars = new XSGrammar[grammars.length];
        for (int index = 0; index < grammars.length; index++) {
            schemaGrammars[index] = (XSGrammar) grammars[index];
        }
        return schemaGrammars[0].toXSModel(schemaGrammars);
    }

    private static void indexElement(
            XSElementDeclaration element,
            List<Choice> choices,
            Set<XSElementDeclaration> visited) {
        if (!visited.add(element)
                || !(element.getTypeDefinition() instanceof XSComplexTypeDefinition type)
                || type.getParticle() == null) {
            return;
        }
        QName parentName = name(element);
        findChoices(parentName, type.getParticle(), choices, false);
        indexChildElements(type.getParticle(), choices, visited);
    }

    private static void findChoices(
            QName parentName,
            XSParticle particle,
            List<Choice> choices,
            boolean insideRepeatingParticle) {
        if (!(particle.getTerm() instanceof XSModelGroup group)) {
            return;
        }
        boolean repeating = insideRepeatingParticle
                || particle.getMaxOccursUnbounded()
                || particle.getMaxOccurs() > 1;
        if (!repeating && group.getCompositor() == XSModelGroup.COMPOSITOR_CHOICE) {
            List<Branch> branches = new ArrayList<>();
            for (Object object : group.getParticles()) {
                List<ElementUse> elements = branchElements((XSParticle) object, true);
                if (!elements.isEmpty()) {
                    branches.add(new Branch(elements));
                }
            }
            if (branches.size() > 1) {
                choices.add(new Choice(parentName, branches));
            }
        }
        for (Object object : group.getParticles()) {
            findChoices(parentName, (XSParticle) object, choices, repeating);
        }
    }

    private static void indexChildElements(
            XSParticle particle,
            List<Choice> choices,
            Set<XSElementDeclaration> visited) {
        XSTerm term = particle.getTerm();
        if (term instanceof XSElementDeclaration element) {
            indexElement(element, choices, visited);
        } else if (term instanceof XSModelGroup group) {
            for (Object object : group.getParticles()) {
                indexChildElements((XSParticle) object, choices, visited);
            }
        }
    }

    private static List<ElementUse> branchElements(
            XSParticle particle,
            boolean ancestorsRequired) {
        boolean required = ancestorsRequired && particle.getMinOccurs() > 0;
        XSTerm term = particle.getTerm();
        if (term instanceof XSElementDeclaration element) {
            return List.of(new ElementUse(name(element), required));
        }
        if (!(term instanceof XSModelGroup group)) {
            return List.of();
        }

        boolean childrenRequired =
                required && group.getCompositor() != XSModelGroup.COMPOSITOR_CHOICE;
        List<ElementUse> elements = new ArrayList<>();
        for (Object object : group.getParticles()) {
            elements.addAll(branchElements((XSParticle) object, childrenRequired));
        }
        return List.copyOf(elements);
    }

    private static List<Branch> compatibleBranches(
            Choice choice,
            List<DocumentPathTracker.SeenElement> siblings) {
        List<Branch> candidates = choice.branches();
        for (DocumentPathTracker.SeenElement sibling : siblings) {
            List<Branch> containing =
                    branchesContaining(choice.branches(), sibling.name());
            if (!containing.isEmpty()) {
                candidates = branchesContaining(candidates, sibling.name());
            }
        }
        return candidates;
    }

    private static List<Branch> branchesContaining(
            List<Branch> branches,
            QName elementName) {
        return branches.stream()
                .filter(branch -> branch.contains(elementName))
                .toList();
    }

    private static List<QName> remaining(
            Branch branch,
            List<DocumentPathTracker.SeenElement> siblings) {
        List<QName> remaining = new ArrayList<>(branch.requiredNames());
        for (DocumentPathTracker.SeenElement sibling : siblings) {
            remaining.remove(sibling.name());
        }
        return List.copyOf(remaining);
    }

    private static QName name(XSObject object) {
        return new QName(namespace(object), object.getName());
    }

    private static String namespace(XSObject object) {
        return object.getNamespace() == null ? "" : object.getNamespace();
    }

    private record ElementUse(QName name, boolean required) {
    }

    private record Branch(List<ElementUse> elements) {
        private Branch {
            elements = List.copyOf(elements);
        }

        private boolean contains(QName name) {
            return elements.stream().anyMatch(element -> element.name().equals(name));
        }

        private List<QName> names() {
            return elements.stream().map(ElementUse::name).toList();
        }

        private List<QName> requiredNames() {
            return elements.stream()
                    .filter(ElementUse::required)
                    .map(ElementUse::name)
                    .toList();
        }
    }

    private record Choice(QName parentName, List<Branch> branches) {
        private Choice {
            branches = List.copyOf(branches);
        }
    }

    record Match(
            DocumentPathTracker.SeenElement selectedBy,
            List<QName> remainingElements,
            List<QName> attemptedBranch) {
    }

    record IncompleteMatch(
            DocumentPathTracker.SeenElement selectedBy,
            List<QName> remainingElements) {
    }
}
