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
    static SourceSnapshot read(Source source, int maxBytes)
            throws SchemaCompilationException {
        if (!(source instanceof StreamSource streamSource)) {
            throw new SchemaCompilationException(
                    "Only StreamSource schema input is supported.");
        }

        try {
            InputStream inputStream = streamSource.getInputStream();
            if (inputStream != null) {
                try (inputStream) {
                    return new SourceSnapshot(
                            readBytes(inputStream, maxBytes, "Root schema"),
                            null,
                            streamSource.getSystemId());
                }
            }

            Reader reader = streamSource.getReader();
            if (reader != null) {
                try (reader) {
                    CharactersSnapshot snapshot =
                            readCharacters(reader, maxBytes, "Root schema");
                    return new SourceSnapshot(
                            snapshot.bytes(),
                            snapshot.characters(),
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
                try (InputStream fileInput = Files.newInputStream(path)) {
                    return new SourceSnapshot(
                            readBytes(fileInput, maxBytes, "Root schema"),
                            null,
                            path.toUri().toString());
                }
            }
        } catch (SizeLimitExceededException exception) {
            throw new SchemaCompilationException(exception.limitMessage(), exception);
        } catch (IllegalArgumentException | IOException exception) {
            throw new SchemaCompilationException("Could not read the schema source.", exception);
        }

        throw new SchemaCompilationException("The schema Source has no content or system ID.");
    }

    static byte[] readBytes(InputStream input, int maxBytes, String description)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if ((long) bytes.size() + count > maxBytes) {
                throw sizeLimitExceeded(description, maxBytes);
            }
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    static CharactersSnapshot readCharacters(
            Reader reader,
            int maxBytes,
            String description)
            throws IOException {
        StringBuilder characters = new StringBuilder(Math.min(maxBytes, 8192));
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            // UTF-8 never uses fewer bytes than the number of UTF-16 code units.
            if ((long) characters.length() + count > maxBytes) {
                throw sizeLimitExceeded(description, maxBytes);
            }
            characters.append(buffer, 0, count);
        }
        String text = characters.toString();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw sizeLimitExceeded(description, maxBytes);
        }
        return new CharactersSnapshot(text, bytes);
    }

    static SizeLimitExceededException sizeLimitExceeded(
            String description,
            long maxBytes) {
        return new SizeLimitExceededException(
                description + " exceeds its configured limit of " + maxBytes + " bytes.");
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

    record CharactersSnapshot(String characters, byte[] bytes) {
    }

    static final class SizeLimitExceededException extends IOException {
        private final String limitMessage;

        SizeLimitExceededException(String message) {
            super(message);
            this.limitMessage = message;
        }

        String limitMessage() {
            return limitMessage;
        }
    }
}
