package kr.dayvoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static JsonNode read(String value) {
        try { return MAPPER.readTree(value); }
        catch (Exception e) { throw new IllegalArgumentException("잘못된 JSON 응답입니다."); }
    }
    static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("데이터 변환 실패"); }
    }
    static ObjectNode object() { return MAPPER.createObjectNode(); }
    static Object plain(JsonNode node) { return MAPPER.convertValue(node, Object.class); }
    static String str(JsonNode node, String field) { return node.path(field).asText(""); }
}
