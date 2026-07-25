package io.github.khopland.xsd.validation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.validation.Schema;
import org.apache.xerces.dom.DOMInputImpl;
import org.apache.xerces.jaxp.validation.XMLSchemaFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.ls.LSException;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

final class XercesSchemaCompiler {
    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    private XercesSchemaCompiler() {
    }

    static CompiledSchema compile(
            Source source,
            LSResourceResolver resourceResolver)
            throws SchemaCompilationException {
        SourceSnapshot snapshot = SourceSnapshot.read(source);
        Document document = parseForMetadata(snapshot);

        if (snapshot.systemId() == null
                && resourceResolver == null
                && hasRelativeDependencies(document)) {
            throw new SchemaCompilationException(
                    "A schema with relative imports or includes needs a Source system ID.");
        }

        try {
            LocalSchemaResolver resolver = new LocalSchemaResolver(resourceResolver);
            XMLSchemaFactory factory = new XMLSchemaFactory();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true);
            factory.setResourceResolver(resolver);

            Schema schema = factory.newSchema(snapshot.asSource());
            String targetNamespace = document.getDocumentElement().getAttribute("targetNamespace");
            SchemaIdentity identity = new SchemaIdentity(
                    targetNamespace,
                    sha256(snapshot.bytes(), resolver.dependencies()));
            return new CompiledSchema(
                    schema,
                    identity,
                    ChoiceIndex.from(schema, targetNamespace));
        } catch (SAXException | RuntimeException exception) {
            throw new SchemaCompilationException("The XSD 1.0 schema could not be compiled.", exception);
        }
    }

    private static Document parseForMetadata(SourceSnapshot snapshot)
            throws SchemaCompilationException {
        try {
            DocumentBuilderFactory factory =
                    new org.apache.xerces.jaxp.DocumentBuilderFactoryImpl();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            InputSource input = new InputSource(new ByteArrayInputStream(snapshot.bytes()));
            input.setSystemId(snapshot.systemId());
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entity resolution is disabled.");
            });
            Document document = builder.parse(input);
            Element root = document.getDocumentElement();
            if (!XSD_NAMESPACE.equals(root.getNamespaceURI())
                    || !"schema".equals(root.getLocalName())) {
                throw new SchemaCompilationException("The schema source is not an XSD schema.");
            }
            return document;
        } catch (SchemaCompilationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SchemaCompilationException("Could not inspect the schema safely.", exception);
        }
    }

    private static boolean hasRelativeDependencies(Document document) {
        return hasRelativeDependency(document, "include")
                || hasRelativeDependency(document, "import")
                || hasRelativeDependency(document, "redefine");
    }

    private static boolean hasRelativeDependency(Document document, String localName) {
        var dependencies = document.getElementsByTagNameNS(XSD_NAMESPACE, localName);
        for (int index = 0; index < dependencies.getLength(); index++) {
            String location = ((Element) dependencies.item(index)).getAttribute("schemaLocation");
            if (!location.isEmpty() && !java.net.URI.create(location).isAbsolute()) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(byte[] root, Map<String, byte[]> dependencies)
            throws SchemaCompilationException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, root);
            dependencies.forEach((systemId, bytes) -> {
                updateDigest(digest, systemId.getBytes(StandardCharsets.UTF_8));
                updateDigest(digest, bytes);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new SchemaCompilationException("SHA-256 is not available.", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static final class LocalSchemaResolver implements LSResourceResolver {
        private final LSResourceResolver delegate;
        private final Map<String, byte[]> dependencies = new TreeMap<>();

        private LocalSchemaResolver(LSResourceResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public LSInput resolveResource(
                String type,
                String namespaceUri,
                String publicId,
                String systemId,
                String baseUri) {
            try {
                if (!XSD_NAMESPACE.equals(type)) {
                    throw new LSException(
                            LSException.PARSE_ERR,
                            "Only XSD dependencies are allowed.");
                }
                if (delegate != null) {
                    LSInput input = delegate.resolveResource(
                            type,
                            namespaceUri,
                            publicId,
                            systemId,
                            baseUri);
                    if (input != null) {
                        return capture(input, publicId, systemId, baseUri);
                    }
                }
                URI resolved = baseUri == null
                        ? URI.create(systemId)
                        : URI.create(baseUri).resolve(systemId);
                if (!"file".equalsIgnoreCase(resolved.getScheme())) {
                    throw new LSException(
                            LSException.PARSE_ERR,
                            "Only local file schema dependencies are allowed.");
                }
                byte[] bytes = Files.readAllBytes(Path.of(resolved));
                dependencies.put(resolved.toString(), bytes);
                return new DOMInputImpl(
                        publicId,
                        resolved.toString(),
                        baseUri,
                        new ByteArrayInputStream(bytes),
                        null);
            } catch (IllegalArgumentException | IOException exception) {
                throw new LSException(
                        LSException.PARSE_ERR,
                        "Could not resolve a local schema dependency.");
            }
        }

        Map<String, byte[]> dependencies() {
            return dependencies;
        }

        private LSInput capture(
                LSInput input,
                String publicId,
                String requestedSystemId,
                String baseUri)
                throws IOException {
            byte[] bytes;
            String encoding = input.getEncoding();
            if (input.getByteStream() != null) {
                bytes = input.getByteStream().readAllBytes();
            } else if (input.getCharacterStream() != null) {
                bytes = read(input.getCharacterStream()).getBytes(StandardCharsets.UTF_8);
                encoding = StandardCharsets.UTF_8.name();
            } else if (input.getStringData() != null) {
                bytes = input.getStringData().getBytes(StandardCharsets.UTF_8);
                encoding = StandardCharsets.UTF_8.name();
            } else {
                throw new LSException(
                        LSException.PARSE_ERR,
                        "The explicit schema resolver returned no content.");
            }

            String resolvedSystemId = resolvedSystemId(
                    input.getSystemId(),
                    requestedSystemId,
                    baseUri);
            dependencies.put(resolvedSystemId, bytes);
            return new DOMInputImpl(
                    input.getPublicId() == null ? publicId : input.getPublicId(),
                    resolvedSystemId,
                    baseUri,
                    new ByteArrayInputStream(bytes),
                    encoding);
        }

        private static String read(Reader reader) throws IOException {
            StringWriter text = new StringWriter();
            reader.transferTo(text);
            return text.toString();
        }

        private static String resolvedSystemId(
                String suppliedSystemId,
                String requestedSystemId,
                String baseUri) {
            String systemId = suppliedSystemId == null
                    ? requestedSystemId
                    : suppliedSystemId;
            if (systemId == null) {
                throw new LSException(
                        LSException.PARSE_ERR,
                        "The explicit schema resolver returned no system ID.");
            }
            return baseUri == null
                    ? systemId
                    : URI.create(baseUri).resolve(systemId).toString();
        }
    }

    record CompiledSchema(
            Schema schema,
            SchemaIdentity identity,
            ChoiceIndex choiceIndex) {
    }
}
