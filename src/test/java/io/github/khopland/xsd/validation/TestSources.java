package io.github.khopland.xsd.validation;

import java.io.StringReader;
import javax.xml.transform.stream.StreamSource;

final class TestSources {
    private TestSources() {}

    static BetterXsdValidator compile(String schema) throws SchemaCompilationException {
        return BetterXsdValidator.compile(new StreamSource(new StringReader(schema)));
    }

    static StreamSource xml(String xml) {
        return new StreamSource(new StringReader(xml));
    }
}
