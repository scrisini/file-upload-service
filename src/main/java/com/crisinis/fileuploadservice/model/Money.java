package com.crisinis.fileuploadservice.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/**
 * An amount of money as a whole number of the currency's minor units (cents), never a double. The
 * scale is the currency's own (ISO 4217: 2 for AUD, 0 for JPY), fixed by the currency.
 *
 * @param amountMinor the amount in minor units, e.g. 123450 for AUD 1,234.50
 * @param currency    ISO 4217 code
 * @param scale       the currency's minor-unit digits, so readers can show {@code amountMinor} without a table
 */
public record Money(long amountMinor, String currency, int scale) {

    public Money {
        Currency known = currency(currency);
        if (scale != known.getDefaultFractionDigits()) {
            throw new IllegalArgumentException(currency + " has " + known.getDefaultFractionDigits() + " decimal places, not " + scale);
        }
    }

    /**
     * Reads a decimal amount ({@code "1234.50"}, {@code "1,234.5"}) in {@code currency}. A boundary:
     * {@code BigDecimal} is used here only to read the text exactly.
     *
     * @throws IllegalArgumentException if it isn't a number, is negative, or has more decimals than the currency
     */
    public static Money parse(String amount, String currency) {
        Currency known = currency(currency);
        int scale = known.getDefaultFractionDigits();
        BigDecimal value;
        try {
            value = new BigDecimal(amount.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + amount + "' is not an amount");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("'" + amount + "' is negative");
        }
        try {
            return new Money(value.setScale(scale, RoundingMode.UNNECESSARY).movePointRight(scale).longValueExact(),
                    known.getCurrencyCode(), scale);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("'" + amount + "' has more decimals than " + known.getCurrencyCode() + " or is too large");
        }
    }

    private static Currency currency(String code) {
        try {
            return Currency.getInstance(code == null ? "" : code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + code + "' is not an ISO 4217 currency");
        }
    }
}
