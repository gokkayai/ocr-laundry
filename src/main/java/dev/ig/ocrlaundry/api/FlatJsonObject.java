package dev.ig.ocrlaundry.api;

import java.util.LinkedHashMap;
import java.util.Map;

final class FlatJsonObject {
    private FlatJsonObject() {
    }

    static Map<String, Object> parse(String json) {
        return new Parser(json).parse();
    }

    static String quote(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    private static final class Parser {
        private final String json;
        private int position;

        private Parser(String json) {
            this.json = json;
        }

        private Map<String, Object> parse() {
            Map<String, Object> values = new LinkedHashMap<>();
            skipWhitespace();
            require('{');
            skipWhitespace();
            if (take('}')) {
                requireEnd();
                return values;
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                require(':');
                skipWhitespace();
                if (values.put(key, parseValue()) != null) {
                    throw error("Duplicate property: " + key);
                }
                skipWhitespace();
                if (take('}')) {
                    requireEnd();
                    return values;
                }
                require(',');
            }
        }

        private Object parseValue() {
            if (current() == '"') {
                return parseString();
            }
            int start = position;
            if (take('-')) {
                // Continue with the digits.
            }
            while (position < json.length() && Character.isDigit(json.charAt(position))) {
                position++;
            }
            if (position == start || (position == start + 1 && json.charAt(start) == '-')) {
                throw error("Expected a string or integer value");
            }
            try {
                return Integer.parseInt(json.substring(start, position));
            } catch (NumberFormatException exception) {
                throw error("Integer is outside the supported range");
            }
        }

        private String parseString() {
            require('"');
            StringBuilder value = new StringBuilder();
            while (position < json.length()) {
                char character = json.charAt(position++);
                if (character == '"') {
                    return value.toString();
                }
                if (character != '\\') {
                    if (character < 0x20) {
                        throw error("Control character in string");
                    }
                    value.append(character);
                    continue;
                }
                if (position >= json.length()) {
                    throw error("Incomplete escape sequence");
                }
                char escaped = json.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(parseUnicodeEscape());
                    default -> throw error("Unsupported escape sequence");
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() {
            if (position + 4 > json.length()) {
                throw error("Incomplete Unicode escape");
            }
            String digits = json.substring(position, position + 4);
            position += 4;
            try {
                return (char) Integer.parseInt(digits, 16);
            } catch (NumberFormatException exception) {
                throw error("Invalid Unicode escape");
            }
        }

        private void require(char expected) {
            if (!take(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private boolean take(char expected) {
            if (position < json.length() && json.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private char current() {
            if (position >= json.length()) {
                throw error("Unexpected end of JSON");
            }
            return json.charAt(position);
        }

        private void skipWhitespace() {
            while (position < json.length() && Character.isWhitespace(json.charAt(position))) {
                position++;
            }
        }

        private void requireEnd() {
            skipWhitespace();
            if (position != json.length()) {
                throw error("Unexpected content after object");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + position);
        }
    }
}
