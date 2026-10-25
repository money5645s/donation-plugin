package kr.unofficialdonation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON reader/writer for this plugin's fixed request and configuration shapes. */
final class Json {
    private Json() { }

    static Object parse(String input) {
        Parser parser = new Parser(input);
        Object result = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) throw new IllegalArgumentException("JSON 데이터 뒤에 올바르지 않은 내용이 있습니다.");
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("JSON 객체가 필요합니다.");
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("JSON 배열이 필요합니다.");
        return (List<Object>) list;
    }

    static String string(Object value) { return value instanceof String string ? string : null; }

    static Boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    static Long longValue(Object value) {
        if (value instanceof Number number && Math.rint(number.doubleValue()) == number.doubleValue()
                && number.doubleValue() >= Long.MIN_VALUE && number.doubleValue() <= Long.MAX_VALUE) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try { return Long.parseLong(text.trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    static String write(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output);
        return output.toString();
    }

    static String writePretty(Object value) {
        StringBuilder output = new StringBuilder();
        writePretty(value, output, 0);
        return output.append('\n').toString();
    }

    private static void write(Object value, StringBuilder output) {
        if (value == null) output.append("null");
        else if (value instanceof String string) writeString(string, output);
        else if (value instanceof Number || value instanceof Boolean) output.append(value);
        else if (value instanceof Map<?, ?> map) {
            output.append('{'); boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) output.append(','); first = false;
                writeString(String.valueOf(entry.getKey()), output); output.append(':'); write(entry.getValue(), output);
            }
            output.append('}');
        } else if (value instanceof Iterable<?> items) {
            output.append('['); boolean first = true;
            for (Object item : items) { if (!first) output.append(','); first = false; write(item, output); }
            output.append(']');
        } else throw new IllegalArgumentException("지원하지 않는 JSON 값입니다.");
    }

    private static void writePretty(Object value, StringBuilder output, int depth) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            write(value, output);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) { output.append("{}"); return; }
            output.append("{\n");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(output, depth + 1);
                writeString(String.valueOf(entry.getKey()), output);
                output.append(": ");
                writePretty(entry.getValue(), output, depth + 1);
                if (++index < map.size()) output.append(',');
                output.append('\n');
            }
            indent(output, depth); output.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            iterable.forEach(items::add);
            if (items.isEmpty()) { output.append("[]"); return; }
            output.append("[\n");
            for (int index = 0; index < items.size(); index++) {
                indent(output, depth + 1);
                writePretty(items.get(index), output, depth + 1);
                if (index + 1 < items.size()) output.append(',');
                output.append('\n');
            }
            indent(output, depth); output.append(']');
        } else throw new IllegalArgumentException("지원하지 않는 JSON 값입니다.");
    }

    private static void indent(StringBuilder output, int depth) { output.append("  ".repeat(depth)); }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\""); case '\\' -> output.append("\\\\"); case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f"); case '\n' -> output.append("\\n"); case '\r' -> output.append("\\r"); case '\t' -> output.append("\\t");
                default -> { if (character < 0x20) output.append("\\u%04x".formatted((int) character)); else output.append(character); }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String input;
        private int index;
        Parser(String input) { this.input = input; }
        boolean atEnd() { return index >= input.length(); }
        void skipWhitespace() { while (!atEnd() && Character.isWhitespace(input.charAt(index))) index++; }
        Object readValue() {
            skipWhitespace(); if (atEnd()) throw new IllegalArgumentException("JSON 값이 필요합니다.");
            return switch (input.charAt(index)) {
                case '{' -> readObject(); case '[' -> readArray(); case '"' -> readString();
                case 't' -> literal("true", Boolean.TRUE); case 'f' -> literal("false", Boolean.FALSE); case 'n' -> literal("null", null);
                default -> readNumber();
            };
        }
        Object literal(String literal, Object value) {
            if (!input.startsWith(literal, index)) throw new IllegalArgumentException("올바르지 않은 JSON 리터럴입니다.");
            index += literal.length(); return value;
        }
        Map<String, Object> readObject() {
            expect('{'); skipWhitespace(); Map<String, Object> object = new LinkedHashMap<>();
            if (consume('}')) return object;
            do { skipWhitespace(); if (atEnd() || input.charAt(index) != '"') throw new IllegalArgumentException("JSON 키가 필요합니다.");
                String key = readString(); skipWhitespace(); expect(':'); object.put(key, readValue()); skipWhitespace();
            } while (consume(',')); expect('}'); return object;
        }
        List<Object> readArray() {
            expect('['); skipWhitespace(); List<Object> array = new ArrayList<>(); if (consume(']')) return array;
            do { array.add(readValue()); skipWhitespace(); } while (consume(',')); expect(']'); return array;
        }
        String readString() {
            expect('"'); StringBuilder result = new StringBuilder();
            while (!atEnd()) { char c = input.charAt(index++); if (c == '"') return result.toString(); if (c != '\\') { result.append(c); continue; }
                if (atEnd()) throw new IllegalArgumentException("올바르지 않은 이스케이프 문자입니다."); char escaped = input.charAt(index++);
                switch (escaped) { case '"' -> result.append('"'); case '\\' -> result.append('\\'); case '/' -> result.append('/'); case 'b' -> result.append('\b'); case 'f' -> result.append('\f'); case 'n' -> result.append('\n'); case 'r' -> result.append('\r'); case 't' -> result.append('\t'); case 'u' -> result.append((char) Integer.parseInt(readChars(4), 16)); default -> throw new IllegalArgumentException("올바르지 않은 이스케이프 문자입니다."); }
            } throw new IllegalArgumentException("끝나지 않은 JSON 문자열입니다.");
        }
        Number readNumber() {
            int start = index; if (consume('-')) { } while (!atEnd() && Character.isDigit(input.charAt(index))) index++;
            if (consume('.')) while (!atEnd() && Character.isDigit(input.charAt(index))) index++;
            if (!atEnd() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) { index++; if (!atEnd() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++; while (!atEnd() && Character.isDigit(input.charAt(index))) index++; }
            try { return Double.parseDouble(input.substring(start, index)); } catch (NumberFormatException exception) { throw new IllegalArgumentException("올바르지 않은 JSON 숫자입니다."); }
        }
        String readChars(int length) { if (index + length > input.length()) throw new IllegalArgumentException("올바르지 않은 유니코드 이스케이프 문자입니다."); String result = input.substring(index, index + length); index += length; return result; }
        boolean consume(char expected) { if (!atEnd() && input.charAt(index) == expected) { index++; return true; } return false; }
        void expect(char expected) { skipWhitespace(); if (!consume(expected)) throw new IllegalArgumentException("'" + expected + "' 문자가 필요합니다."); }
    }
}
