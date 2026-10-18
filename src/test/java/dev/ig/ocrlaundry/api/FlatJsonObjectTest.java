package dev.ig.ocrlaundry.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class FlatJsonObjectTest {
    @Test
    void parsesTheJobRequestShape() {
        Map<String, Object> values = FlatJsonObject.parse(
                " { \"documentId\" : \"small\", \"priority\" : -3 } ");

        assertEquals("small", values.get("documentId"));
        assertEquals(-3, values.get("priority"));
    }

    @Test
    void rejectsNestedOrDuplicateValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FlatJsonObject.parse("{\"documentId\": {}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> FlatJsonObject.parse("{\"priority\":1,\"priority\":2}"));
    }

    @Test
    void quotesJsonControlCharacters() {
        assertEquals("\"line\\n\\\"value\\\"\"", FlatJsonObject.quote("line\n\"value\""));
    }
}
