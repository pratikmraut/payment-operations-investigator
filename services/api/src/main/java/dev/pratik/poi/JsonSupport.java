package dev.pratik.poi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class JsonSupport {
  public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private final ObjectMapper mapper;

  public JsonSupport(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public ObjectNode object(String value) {
    try {
      return (ObjectNode) mapper.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException("Stored JSON is invalid.", e);
    }
  }

  public String write(JsonNode node) {
    return node.toString();
  }

  public ObjectNode object() {
    return mapper.createObjectNode();
  }

  public ObjectNode value(Object value) {
    return mapper.valueToTree(value);
  }

  public static String requiredText(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual() || value.asText().isBlank())
      throw new ApiException(422, "INVALID_DATA", "A required field is absent: " + name);
    return value.asText();
  }

  public static long minor(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
      throw new ApiException(
          422, "INVALID_AMOUNT", name + " must be an integer within the JSON-safe range.");
    return safeInteger(value.longValue(), name);
  }

  public static long safeInteger(long value, String name) {
    if (value < -MAX_SAFE_INTEGER || value > MAX_SAFE_INTEGER)
      throw new ApiException(
          422,
          "AMOUNT_OUT_OF_RANGE",
          name + " exceeds the JSON-safe integer range [-9007199254740991, 9007199254740991].");
    return value;
  }

  public static void validateMoneyFields(JsonNode node, String currency) {
    if (node.isObject()) {
      node.fields()
          .forEachRemaining(
              field -> {
                if (field.getKey().endsWith("Minor") && !field.getValue().isNull())
                  minor(node, field.getKey());
                if (field.getKey().equals("currency")
                    && !currency.equals(field.getValue().asText()))
                  throw new ApiException(
                      422,
                      "CURRENCY_MISMATCH",
                      "All monetary evidence must match the case currency.");
                validateMoneyFields(field.getValue(), currency);
              });
    } else if (node.isArray()) {
      node.forEach(child -> validateMoneyFields(child, currency));
    }
  }
}
