package io.github.khopland.xsd.validation;

import java.io.StringReader;
import javax.xml.transform.stream.StreamSource;

final class TestSources {
    static final String CHOICE_SCHEMA = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                       targetNamespace="urn:contact"
                       xmlns="urn:contact"
                       elementFormDefault="qualified">
              <xs:element name="contact">
                <xs:complexType>
                  <xs:choice minOccurs="0">
                    <xs:sequence>
                      <xs:element name="postalAddress" type="xs:string"/>
                      <xs:element name="postalCode" type="xs:string"/>
                    </xs:sequence>
                    <xs:element name="sms" type="xs:string"/>
                  </xs:choice>
                </xs:complexType>
              </xs:element>
            </xs:schema>
            """;

    private TestSources() {}

    static BetterXsdValidator compile(String schema) throws SchemaCompilationException {
        return BetterXsdValidator.compile(new StreamSource(new StringReader(schema)));
    }

    static StreamSource xml(String xml) {
        return new StreamSource(new StringReader(xml));
    }
}
