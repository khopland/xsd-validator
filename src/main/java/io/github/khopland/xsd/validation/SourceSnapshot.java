package io.github.khopland.xsd.validation;

import org.jspecify.annotations.Nullable;
import org.xml.sax.InputSource;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

record SourceSnapshot(
        byte[] bytes,
        @Nullable String characters,
        @Nullable String systemId) {
    static SourceSnapshot read(Source source) throws SchemaCompilationException {
        if (!(source instanceof StreamSource streamSource)) {
            throw new SchemaCompilationException(
                    "Only StreamSource schema input is supported.");
        }

        try {
            InputStream inputStream = streamSource.getInputStream();
            if (inputStream != null) {
                try (inputStream) {
                    return new SourceSnapshot(
                            inputStream.readAllBytes(),
                            null,
                            streamSource.getSystemId());
                }
            }

            Reader reader = streamSource.getReader();
            if (reader != null) {
                try (reader) {
                    StringWriter text = new StringWriter();
                    reader.transferTo(text);
                    String characters = text.toString();
                    return new SourceSnapshot(
                            characters.getBytes(StandardCharsets.UTF_8),
                            characters,
                            streamSource.getSystemId());
                }
            }

            if (streamSource.getSystemId() != null) {
                URI uri = URI.create(streamSource.getSystemId());
                if (uri.isAbsolute() && !"file".equalsIgnoreCase(uri.getScheme())) {
                    throw new SchemaCompilationException(
                            "Only local file schema system IDs are allowed.");
                }
                Path path = uri.isAbsolute() ? Path.of(uri) : Path.of(streamSource.getSystemId());
                return new SourceSnapshot(
                        Files.readAllBytes(path),
                        null,
                        path.toUri().toString());
            }
        } catch (IllegalArgumentException | IOException exception) {
            throw new SchemaCompilationException("Could not read the schema source.", exception);
        }

        throw new SchemaCompilationException("The schema Source has no content or system ID.");
    }

    StreamSource asSource() {
        StreamSource source = characters == null
                ? new StreamSource(new ByteArrayInputStream(bytes))
                : new StreamSource(new StringReader(characters));
        source.setSystemId(systemId);
        return source;
    }

    InputSource asInputSource() {
        InputSource source = characters == null
                ? new InputSource(new ByteArrayInputStream(bytes))
                : new InputSource(new StringReader(characters));
        source.setSystemId(systemId);
        return source;
    }
}
