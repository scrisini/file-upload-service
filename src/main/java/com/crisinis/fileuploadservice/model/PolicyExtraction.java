package com.crisinis.fileuploadservice.model;

import java.time.LocalDate;
import java.util.List;

/**
 * An insurance policy as the model read it from the document. Every field says how sure the model
 * was; a field it didn't find, or whose value didn't pass validation, has a null value (and a
 * warning says why). Personal data: never log these values.
 *
 * @param warnings values the model gave that were dropped, and why (e.g. "endDate: '31/02/2026' is not a date")
 */
public record PolicyExtraction(
        Field<String> insurer,
        Field<String> policyNumber,
        Field<PolicyType> policyType,
        Holder holder,
        List<InsuredPerson> insuredPersons,
        Field<LocalDate> startDate,
        Field<LocalDate> endDate,
        Field<Money> premium,
        Field<PremiumFrequency> premiumFrequency,
        Field<Money> sumInsured,
        Field<Money> excess,
        Field<String> currency,
        List<String> warnings) {

    public PolicyExtraction {
        insuredPersons = List.copyOf(insuredPersons);
        warnings = List.copyOf(warnings);
    }

    /** How many fields have a value: a rough measure of how much was read, safe to log. */
    public int fieldsFound() {
        int found = count(insurer) + count(policyNumber) + count(policyType) + count(startDate) + count(endDate)
                + count(premium) + count(premiumFrequency) + count(sumInsured) + count(excess) + count(currency)
                + count(holder.name()) + count(holder.dateOfBirth()) + count(holder.address());
        for (InsuredPerson person : insuredPersons) {
            found += count(person.name()) + count(person.dateOfBirth());
        }
        return found;
    }

    private static int count(Field<?> field) {
        return field.value() == null ? 0 : 1;
    }

    /**
     * One value and the model's confidence in it.
     *
     * @param value      null when not found or not valid
     * @param confidence 0 to 1; 0 when the value is null
     */
    public record Field<T>(T value, double confidence) {

        public Field {
            confidence = value == null ? 0 : Math.max(0, Math.min(1, confidence));
        }

        public static <T> Field<T> missing() {
            return new Field<>(null, 0);
        }
    }

    /** The policy holder. */
    public record Holder(Field<String> name, Field<LocalDate> dateOfBirth, Field<String> address) {
    }

    /** A person the policy covers. */
    public record InsuredPerson(Field<String> name, Field<LocalDate> dateOfBirth) {
    }

    /** The kind of cover. */
    public enum PolicyType {
        HOME, CONTENTS, HOME_AND_CONTENTS, MOTOR, LIFE, HEALTH, TRAVEL, LANDLORD, BUSINESS, PET, OTHER
    }

    /** How often the premium is paid. */
    public enum PremiumFrequency {
        ANNUAL, SEMI_ANNUAL, QUARTERLY, MONTHLY, FORTNIGHTLY, WEEKLY, SINGLE
    }
}
