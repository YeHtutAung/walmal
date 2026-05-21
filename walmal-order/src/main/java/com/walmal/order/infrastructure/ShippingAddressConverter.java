package com.walmal.order.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmal.order.domain.ShippingAddress;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA {@link AttributeConverter} that serialises {@link ShippingAddress} to a JSON string
 * for storage in the {@code shipping_address} JSONB column on {@code order_orders}.
 *
 * <p>Uses its own {@link ObjectMapper} instance so that this infrastructure class has
 * no dependency on the application context's primary ObjectMapper bean configuration.</p>
 */
@Converter(autoApply = false)
public class ShippingAddressConverter implements AttributeConverter<ShippingAddress, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(ShippingAddress address) {
        if (address == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(address);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ShippingAddress to JSON", e);
        }
    }

    @Override
    public ShippingAddress convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, ShippingAddress.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize ShippingAddress from JSON: " + json, e);
        }
    }
}
