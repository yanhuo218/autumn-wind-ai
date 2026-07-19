package io.github.yanhuo218.autumnwind.inference.interfaces.http;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.util.UUID;

public final class CanonicalUuidDeserializer extends ValueDeserializer<UUID> {

    @Override
    public UUID deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return (UUID) context.handleUnexpectedToken(UUID.class, parser);
        }
        String raw = parser.getString();
        try {
            UUID value = UUID.fromString(raw);
            if (!value.toString().equalsIgnoreCase(raw)) {
                return (UUID) context.handleWeirdStringValue(UUID.class, raw, "UUID 必须使用规范格式。");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            return (UUID) context.handleWeirdStringValue(UUID.class, raw, "UUID 必须使用规范格式。");
        }
    }
}
