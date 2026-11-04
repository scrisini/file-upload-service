package com.crisinis.fileuploadservice.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import com.crisinis.fileuploadservice.model.Money;
import com.crisinis.fileuploadservice.model.PolicyExtraction;
import com.crisinis.fileuploadservice.model.PolicyExtraction.PolicyType;
import com.crisinis.fileuploadservice.model.PolicyExtraction.PremiumFrequency;
import com.crisinis.fileuploadservice.support.SamplePolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PolicyParserTests {

    static final String FULL = SamplePolicy.JSON;

    private final PolicyParser parser = new PolicyParser(JsonMapper.builder().build());

    @Test
    void readsEveryField() {
        PolicyExtraction policy = parser.parse(FULL);

        assertThat(policy.insurer().value()).isEqualTo("Example Insurance Ltd");
        assertThat(policy.insurer().confidence()).isEqualTo(0.98);
        assertThat(policy.policyNumber().value()).isEqualTo("HPC-0042-7781");
        assertThat(policy.policyType().value()).isEqualTo(PolicyType.HOME_AND_CONTENTS);
        assertThat(policy.holder().dateOfBirth().value()).isEqualTo(LocalDate.of(1984, 7, 21));
        assertThat(policy.insuredPersons()).singleElement().satisfies(p -> {
            assertThat(p.name().value()).isEqualTo("John Citizen");
            assertThat(p.dateOfBirth().value()).isNull();
        });
        assertThat(policy.endDate().value()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(policy.currency().value()).isEqualTo("AUD");
        assertThat(policy.premium().value()).isEqualTo(new Money(184260, "AUD", 2));
        assertThat(policy.premiumFrequency().value()).isEqualTo(PremiumFrequency.MONTHLY);
        assertThat(policy.sumInsured().value()).isEqualTo(new Money(85000000, "AUD", 2));
        assertThat(policy.excess().confidence()).as("clamped to 1").isEqualTo(1.0);
        assertThat(policy.warnings()).isEmpty();
        assertThat(policy.fieldsFound()).isEqualTo(14);
    }

    @Test
    void findsTheJsonInsideProseOrCodeFences() {
        assertThat(parser.parse("Here you go:\n```json\n" + FULL + "\n```\nHope that helps.").policyNumber().value())
                .isEqualTo("HPC-0042-7781");
    }

    @Test
    void anInvalidValueBecomesAMissingFieldWithAWarningThatDoesntQuoteIt() {
        PolicyExtraction policy = parser.parse("""
                {"endDate": {"value": "31/02/2026", "confidence": 0.9},
                 "policyType": {"value": "SPACESHIP", "confidence": 0.9},
                 "currency": {"value": "XYZ", "confidence": 0.9},
                 "premium": {"value": "12.50", "confidence": 0.9},
                 "holder": {"dateOfBirth": {"value": "Jane Citizen 1984", "confidence": 0.9}}}""");

        assertThat(policy.endDate().value()).isNull();
        assertThat(policy.endDate().confidence()).isZero();
        assertThat(policy.policyType().value()).isNull();
        assertThat(policy.currency().value()).isNull();
        assertThat(policy.premium().value()).as("no currency to read it in").isNull();
        assertThat(policy.warnings()).hasSize(5)
                .anySatisfy(w -> assertThat(w).startsWith("endDate:").contains("YYYY-MM-DD"))
                .anySatisfy(w -> assertThat(w).startsWith("policyType:").contains("allowed values"))
                .anySatisfy(w -> assertThat(w).startsWith("premium:"))
                .allSatisfy(w -> assertThat(w).doesNotContain("31/02").doesNotContain("Jane").doesNotContain("SPACESHIP"));
    }

    @Test
    void amountsFollowTheCurrencysDecimalPlaces() {
        PolicyExtraction yen = parser.parse("""
                {"currency": {"value": "JPY", "confidence": 1}, "premium": {"value": "52000", "confidence": 1},
                 "excess": {"value": "100.50", "confidence": 1}, "sumInsured": {"value": "-5", "confidence": 1}}""");

        assertThat(yen.premium().value()).isEqualTo(new Money(52000, "JPY", 0));
        assertThat(yen.excess().value()).as("JPY has no minor unit").isNull();
        assertThat(yen.sumInsured().value()).as("negative").isNull();
        assertThat(yen.warnings()).hasSize(2);
    }

    @Test
    void missingFieldsAreMissingNotErrors() {
        PolicyExtraction policy = parser.parse("{}");

        assertThat(policy.fieldsFound()).isZero();
        assertThat(policy.warnings()).isEmpty();
        assertThat(policy.holder().name().value()).isNull();
        assertThat(policy.insuredPersons()).isEmpty();
    }

    @Test
    void anAnswerWithoutAJsonObjectFailsTheAttemptButMayBeRetried() {
        for (String answer : new String[] {"I can't read this document.", "[1, 2]", "{not json}", "", null}) {
            assertThatThrownBy(() -> parser.parse(answer)).as(String.valueOf(answer))
                    .isInstanceOfSatisfying(ExtractionException.class, e -> {
                        assertThat(e.retryable()).isTrue();
                        assertThat(e.reason()).isEqualTo("ANSWER_NOT_JSON");
                    });
        }
    }

    @Test
    void boundsWhatTheModelCanMakeItKeep() {
        StringBuilder many = new StringBuilder("{\"insurer\": {\"value\": \"" + "x".repeat(2000) + "\", \"confidence\": 1}, \"insuredPersons\": [");
        for (int i = 0; i < 30; i++) {
            many.append(i == 0 ? "" : ",").append("{\"name\": {\"value\": \"P").append(i).append("\", \"confidence\": 1}}");
        }
        PolicyExtraction policy = parser.parse(many.append("]}").toString());

        assertThat(policy.insurer().value()).hasSize(PolicyParser.MAX_TEXT);
        assertThat(policy.insuredPersons()).hasSize(PolicyParser.MAX_INSURED_PERSONS);
        assertThat(policy.warnings()).singleElement().asString().startsWith("insuredPersons:");
    }
}
