package com.walmal.order.domain;

/**
 * Value object representing a delivery address snapshot.
 *
 * <p>Stored as JSONB on {@link Order} via {@code @JdbcTypeCode(SqlTypes.JSON)}.
 * Being a record, it is immutable after construction — the address is fixed at the time the
 * order is placed and never updated even if the customer later edits their address book.</p>
 *
 * @param line1      first address line (street number and name)
 * @param line2      optional second line (apartment, suite, etc.); may be null
 * @param city       city or town name
 * @param country    ISO-3166 country code (e.g., "US", "GB")
 * @param postalCode postal or ZIP code
 */
public record ShippingAddress(
        String line1,
        String line2,
        String city,
        String country,
        String postalCode
) {}
