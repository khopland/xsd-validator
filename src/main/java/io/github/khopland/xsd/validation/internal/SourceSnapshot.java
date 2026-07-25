package io.github.khopland.xsd.validation.internal;

import io.github.khopland.xsd.validation.SchemaCompilationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

record SourceSnapshot(byte[] bytes, String systemId) {
    static SourceSnapshot read(Source source) throws SchemaCompilationException {
        if (!(source instanceof StreamSource streamSource)) {
            throw new SchemaCompilationException(
                    "Milestone 0 supports StreamSource schemas; convert this Source to a StreamSource.");
        }

        try {
            InputStream inputStream = streamSource.getInputStream();
            if (inputStream != null) {
                return new SourceSnapshot(inputStream.readAllBytes(), streamSource.getSystemId());
            }

            Reader reader = streamSource.getReader();
            if (reader != null) {
                StringWriter text = new StringWriter();
                reader.transferTo(text);
                return new SourceSnapshot(
                        text.toString().getBytes(StandardCharsets.UTF_8),
                        streamSource.getSystemId());
            }

            if (streamSource.getSystemId() != null) {
                URI uri = URI.create(streamSource.getSystemId());
                Path path = uri.isAbsolute() ? Path.of(uri) : Path.of(streamSource.getSystemId());
                return new SourceSnapshot(Files.readAllBytes(path), path.toUri().toString());
            }
        } catch (IllegalArgumentException | IOException exception) {
            throw new SchemaCompilationException("Could not read the schema source.", exception);
        }

        throw new SchemaCompilationException("The schema Source has no content or system ID.");
    }

    StreamSource asSource() {
        StreamSource source = new StreamSource(new ByteArrayInputStream(bytes));
        source.setSystemId(systemId);
        return source;
    }
}
