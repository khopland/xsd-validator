package io.github.khopland.xsd.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

final class DocumentPathTracker extends DefaultHandler {
    private static final int MAX_RETAINED_CHILDREN = 100;
    private static final int MAX_RETAINED_ATTRIBUTES = 100;

    private final ContentHandler delegate;
    private final Deque<Frame> path = new ArrayDeque<>();
    private final Map<QName, Integer> rootCounts = new HashMap<>();
    private Locator locator;

    DocumentPathTracker(ContentHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
        delegate.setDocumentLocator(locator);
    }

    @Override
    public void startDocument() throws SAXException {
        path.clear();
        rootCounts.clear();
        delegate.startDocument();
    }

    @Override
    public void endDocument() throws SAXException {
        delegate.endDocument();
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        delegate.startPrefixMapping(prefix, uri);
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        delegate.endPrefixMapping(prefix);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {
        QName name = new QName(uri == null ? "" : uri, localName(localName, qName));
        Map<QName, Integer> counts = path.isEmpty() ? rootCounts : path.peekLast().childCounts;
        int index = counts.merge(name, 1, Integer::sum);
        path.addLast(new Frame(name, index, line(), attributeNames(attributes)));
        delegate.startElement(uri, localName, qName, attributes);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        delegate.endElement(uri, localName, qName);
        Frame completed = path.removeLast();
        if (!path.isEmpty()) {
            path.peekLast().remember(new SeenElement(completed.name, completed.line));
        }
    }

    @Override
    public void characters(char[] characters, int start, int length) throws SAXException {
        delegate.characters(characters, start, length);
    }

    @Override
    public void ignorableWhitespace(char[] characters, int start, int length)
            throws SAXException {
        delegate.ignorableWhitespace(characters, start, length);
    }

    @Override
    public void processingInstruction(String target, String data) throws SAXException {
        delegate.processingInstruction(target, data);
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        delegate.skippedEntity(name);
    }

    Context context() {
        if (path.isEmpty()) {
            return new Context(
                    "/", null, null, List.of(), List.of(), List.of(), line(), column());
        }

        List<Frame> frames = List.copyOf(path);
        Frame current = frames.get(frames.size() - 1);
        Frame parent = frames.size() > 1 ? frames.get(frames.size() - 2) : null;
        StringBuilder renderedPath = new StringBuilder();
        for (Frame frame : frames) {
            renderedPath.append('/')
                    .append(frame.name.getLocalPart())
                    .append('[')
                    .append(frame.index)
                    .append(']');
        }
        return new Context(
                renderedPath.toString(),
                current.name,
                parent == null ? null : parent.name,
                parent == null ? List.of() : List.copyOf(parent.children),
                List.copyOf(current.children),
                current.attributes,
                line(),
                column());
    }

    private int line() {
        return locator == null ? -1 : locator.getLineNumber();
    }

    private int column() {
        return locator == null ? -1 : locator.getColumnNumber();
    }

    private static String localName(String localName, String qName) {
        if (localName != null && !localName.isEmpty()) {
            return localName;
        }
        int separator = qName.indexOf(':');
        return separator < 0 ? qName : qName.substring(separator + 1);
    }

    private static List<QName> attributeNames(Attributes attributes) {
        List<QName> names = new ArrayList<>();
        for (int index = 0;
                index < attributes.getLength() && names.size() < MAX_RETAINED_ATTRIBUTES;
                index++) {
            String qName = attributes.getQName(index);
            int separator = qName.indexOf(':');
            String prefix = separator < 0 ? "" : qName.substring(0, separator);
            names.add(new QName(
                    attributes.getURI(index),
                    localName(attributes.getLocalName(index), qName),
                    prefix));
        }
        return List.copyOf(names);
    }

    private static final class Frame {
        private final QName name;
        private final int index;
        private final int line;
        private final List<QName> attributes;
        private final Map<QName, Integer> childCounts = new HashMap<>();
        private final List<SeenElement> children = new ArrayList<>();

        private Frame(QName name, int index, int line, List<QName> attributes) {
            this.name = name;
            this.index = index;
            this.line = line;
            this.attributes = attributes;
        }

        private void remember(SeenElement child) {
            if (children.size() == MAX_RETAINED_CHILDREN) {
                children.remove(0);
            }
            children.add(child);
        }
    }

    record SeenElement(QName name, int line) {
    }

    record Context(
            String path,
            QName actualElement,
            QName parentElement,
            List<SeenElement> previousSiblings,
            List<SeenElement> children,
            List<QName> attributes,
            int line,
            int column) {
    }
}
