package io.github.khopland.xsd.validation;

import org.apache.xerces.dom.DOMInputImpl;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.ls.LSException;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.validation.Schema;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

final class XercesSchemaCompiler {
    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    private XercesSchemaCompiler() {
    }

    static CompiledSchema compile(
            Source source,
            @Nullable LSResourceResolver resourceResolver)
            throws SchemaCompilationException {
        return compile(source, resourceResolver, SchemaCompilationLimits.DEFAULT);
    }

    static CompiledSchema compile(
            Source source,
            @Nullable LSResourceResolver resourceResolver,
            SchemaCompilationLimits limits)
            throws SchemaCompilationException {
        SourceSnapshot snapshot = SourceSnapshot.read(source, limits.maxRootSchemaBytes());
        Document document = parseForMetadata(snapshot);

        if (snapshot.systemId() == null
                && resourceResolver == null
                && hasRelativeDependencies(document)) {
            throw new SchemaCompilationException(
                    "A schema with relative imports or includes needs a Source system ID.");
        }

        LocalSchemaResolver resolver = new LocalSchemaResolver(resourceResolver, limits);
        try {
            var factory = XercesCompatibility.schemaFactory();
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
            if (resolver.limitFailure() != null) {
                throw new SchemaCompilationException(resolver.limitFailure(), exception);
            }
            throw new SchemaCompilationException("The XSD 1.0 schema could not be compiled.", exception);
        }
    }

    private static Document parseForMetadata(SourceSnapshot snapshot)
            throws SchemaCompilationException {
        try {
            DocumentBuilderFactory factory = XercesCompatibility.documentBuilderFactory();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entity resolution is disabled.");
            });
            Document document = builder.parse(snapshot.asInputSource());
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
            if (!location.isEmpty()) {
                try {
                    if (!URI.create(location).isAbsolute()) {
                        return true;
                    }
                } catch (IllegalArgumentException exception) {
                    return true;
                }
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
        private final @Nullable LSResourceResolver delegate;
        private final SchemaCompilationLimits limits;
        private final Map<String, byte[]> dependencies = new TreeMap<>();
        private long totalDependencyBytes;
        private @Nullable String limitFailure;

        private LocalSchemaResolver(
                @Nullable LSResourceResolver delegate,
                SchemaCompilationLimits limits) {
            this.delegate = delegate;
            this.limits = limits;
        }

        @Override
        public @Nullable LSInput resolveResource(
                String type,
                @Nullable String namespaceUri,
                @Nullable String publicId,
                @Nullable String systemId,
                @Nullable String baseUri) {
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
                if (systemId == null) {
                    return null;
                }
                URI resolved = baseUri == null
                        ? URI.create(systemId)
                        : URI.create(baseUri).resolve(systemId);
                if (!"file".equalsIgnoreCase(resolved.getScheme())) {
                    throw new LSException(
                            LSException.PARSE_ERR,
                            "Only local file schema dependencies are allowed.");
                }
                String resolvedSystemId = resolved.toString();
                DependencyReadLimit readLimit = dependencyReadLimit(resolvedSystemId);
                byte[] bytes;
                try (var input = Files.newInputStream(Path.of(resolved))) {
                    bytes = readDependencyBytes(input, readLimit);
                }
                rememberDependency(resolvedSystemId, bytes);
                return new DOMInputImpl(
                        publicId,
                        resolvedSystemId,
                        baseUri,
                        new ByteArrayInputStream(bytes),
                        null);
            } catch (SourceSnapshot.SizeLimitExceededException exception) {
                limitFailure = exception.limitMessage();
                throw new LSException(LSException.PARSE_ERR, exception.limitMessage());
            } catch (IllegalArgumentException | IOException exception) {
                throw new LSException(
                        LSException.PARSE_ERR,
                        "Could not resolve a local schema dependency.");
            }
        }

        Map<String, byte[]> dependencies() {
            return dependencies;
        }

        @Nullable String limitFailure() {
            return limitFailure;
        }

        private LSInput capture(
                LSInput input,
                @Nullable String publicId,
                @Nullable String requestedSystemId,
                @Nullable String baseUri)
                throws IOException {
            String resolvedSystemId = resolvedSystemId(
                    input.getSystemId(),
                    requestedSystemId,
                    baseUri);
            byte[] bytes;
            String encoding = input.getEncoding();
            var byteStream = input.getByteStream();
            if (byteStream != null) {
                try (byteStream) {
                    DependencyReadLimit readLimit =
                            dependencyReadLimit(resolvedSystemId);
                    bytes = readDependencyBytes(byteStream, readLimit);
                }
            } else {
                var characterStream = input.getCharacterStream();
                if (characterStream != null) {
                    try (characterStream) {
                        DependencyReadLimit readLimit =
                                dependencyReadLimit(resolvedSystemId);
                        try {
                            bytes = SourceSnapshot.readCharacters(
                                            characterStream,
                                            readLimit.maxBytes(),
                                            "Schema dependency")
                                    .bytes();
                        } catch (SourceSnapshot.SizeLimitExceededException exception) {
                            throw new SourceSnapshot.SizeLimitExceededException(
                                    readLimit.failureMessage());
                        }
                    }
                    encoding = StandardCharsets.UTF_8.name();
                } else {
                    @Nullable String stringData = input.getStringData();
                    if (stringData == null) {
                        throw new LSException(
                                LSException.PARSE_ERR,
                                "The explicit schema resolver returned no content.");
                    }
                    DependencyReadLimit readLimit =
                            dependencyReadLimit(resolvedSystemId);
                    bytes = stringData.getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > readLimit.maxBytes()) {
                        throw new SourceSnapshot.SizeLimitExceededException(
                                readLimit.failureMessage());
                    }
                    encoding = StandardCharsets.UTF_8.name();
                }
            }

            rememberDependency(resolvedSystemId, bytes);
            return new DOMInputImpl(
                    input.getPublicId() == null ? publicId : input.getPublicId(),
                    resolvedSystemId,
                    baseUri,
                    new ByteArrayInputStream(bytes),
                    encoding);
        }

        private byte[] readDependencyBytes(
                InputStream input,
                DependencyReadLimit readLimit)
                throws IOException {
            try {
                return SourceSnapshot.readBytes(
                        input,
                        readLimit.maxBytes(),
                        "Schema dependency");
            } catch (SourceSnapshot.SizeLimitExceededException exception) {
                throw new SourceSnapshot.SizeLimitExceededException(
                        readLimit.failureMessage());
            }
        }

        private DependencyReadLimit dependencyReadLimit(String systemId)
                throws SourceSnapshot.SizeLimitExceededException {
            byte[] previous = dependencies.get(systemId);
            if (previous == null && dependencies.size() >= limits.maxDependencyCount()) {
                throw new SourceSnapshot.SizeLimitExceededException(
                        "Schema dependency count exceeds its configured limit of "
                                + limits.maxDependencyCount() + ".");
            }
            long availableTotal = limits.maxTotalDependencyBytes()
                    - totalDependencyBytes
                    + (previous == null ? 0 : previous.length);
            if (availableTotal <= 0) {
                throw SourceSnapshot.sizeLimitExceeded(
                        "Total schema dependency content",
                        limits.maxTotalDependencyBytes());
            }
            if (availableTotal < limits.maxDependencyBytes()) {
                return new DependencyReadLimit(
                        (int) Math.min(availableTotal, Integer.MAX_VALUE),
                        "Total schema dependency content exceeds its configured limit of "
                                + limits.maxTotalDependencyBytes() + " bytes.");
            }
            return new DependencyReadLimit(
                    limits.maxDependencyBytes(),
                    "Schema dependency exceeds its configured limit of "
                            + limits.maxDependencyBytes() + " bytes.");
        }

        private void rememberDependency(String systemId, byte[] bytes) {
            byte[] previous = dependencies.put(systemId, bytes);
            totalDependencyBytes += bytes.length - (previous == null ? 0 : previous.length);
        }

        private record DependencyReadLimit(int maxBytes, String failureMessage) {
        }

        private static String resolvedSystemId(
                @Nullable String suppliedSystemId,
                @Nullable String requestedSystemId,
                @Nullable String baseUri) {
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
