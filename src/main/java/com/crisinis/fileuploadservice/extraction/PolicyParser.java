package com.crisinis.fileuploadservice.extraction;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.crisinis.fileuploadservice.model.Money;
import com.crisinis.fileuploadservice.model.PolicyExtraction;
import com.crisinis.fileuploadservice.model.PolicyExtraction.Field;
import com.crisinis.fileuploadservice.model.PolicyExtraction.Holder;
import com.crisinis.fileuploadservice.model.PolicyExtraction.InsuredPerson;
import com.crisinis.fileuploadservice.model.PolicyExtraction.PolicyType;
import com.crisinis.fileuploadservice.model.PolicyExtraction.PremiumFrequency;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the model's answer into the policy schema. The answer is untrusted text: anything that
 * isn't a valid value for its field (a date that isn't a date, an unknown currency, an amount with
 * too many decimals) becomes a missing field and a warning, never an error, so one bad field doesn't
 * lose the rest. Only an answer with no JSON object at all fails the attempt.
 */
@Component
public class PolicyParser {

    /** At most this many insured persons are kept, however many the answer lists. */
    static final int MAX_INSURED_PERSONS = 20;
    /** Text values longer than this are cut: names and addresses, not essays. */
    static final int MAX_TEXT = 500;

    private final JsonMapper json;

    public PolicyParser(JsonMapper json) {
        this.json = json;
    }

    public PolicyExtraction parse(String answer) {
        JsonNode root = jsonObject(answer);
        List<String> warnings = new ArrayList<>();
        Field<String> currency = field(root, "currency", warnings, PolicyParser::currencyCode);
        String code = currency.value();
        Function<String, Money> money = amount -> {
            if (code == null) {
                throw new IllegalArgumentException("no currency to read it in");
            }
            return Money.parse(amount, code);
        };
        JsonNode holder = root.path("holder");
        List<InsuredPerson> insured = new ArrayList<>();
        for (JsonNode person : root.path("insuredPersons")) {
            if (insured.size() == MAX_INSURED_PERSONS) {
                warnings.add("insuredPersons: more than " + MAX_INSURED_PERSONS + ", the rest dropped");
                break;
            }
            insured.add(new InsuredPerson(field(person, "name", warnings, PolicyParser::text),
                    field(person, "dateOfBirth", warnings, LocalDate::parse)));
        }
        return new PolicyExtraction(
                field(root, "insurer", warnings, PolicyParser::text),
                field(root, "policyNumber", warnings, PolicyParser::text),
                field(root, "policyType", warnings, value -> PolicyType.valueOf(constant(value))),
                new Holder(field(holder, "name", warnings, PolicyParser::text),
                        field(holder, "dateOfBirth", warnings, LocalDate::parse),
                        field(holder, "address", warnings, PolicyParser::text)),
                insured,
                field(root, "startDate", warnings, LocalDate::parse),
                field(root, "endDate", warnings, LocalDate::parse),
                field(root, "premium", warnings, money),
                field(root, "premiumFrequency", warnings, value -> PremiumFrequency.valueOf(constant(value))),
                field(root, "sumInsured", warnings, money),
                field(root, "excess", warnings, money),
                currency,
                warnings);
    }

    /** The first JSON object in the answer, allowing for prose or code fences around it. */
    private JsonNode jsonObject(String answer) {
        int start = answer == null ? -1 : answer.indexOf('{');
        int end = answer == null ? -1 : answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ExtractionException(true, "ANSWER_NOT_JSON", "the model's answer has no JSON object", null);
        }
        try {
            JsonNode node = json.readTree(answer.substring(start, end + 1));
            if (!node.isObject()) {
                throw new ExtractionException(true, "ANSWER_NOT_JSON", "the model's answer isn't a JSON object", null);
            }
            return node;
        } catch (JacksonException e) {
            throw new ExtractionException(true, "ANSWER_NOT_JSON", "the model's answer isn't valid JSON", e);
        }
    }

    /** {@code {"value": ..., "confidence": ...}} read with {@code reader}; missing, with a warning, if invalid. */
    private static <T> Field<T> field(JsonNode parent, String name, List<String> warnings, Function<String, T> reader) {
        JsonNode node = parent.path(name);
        JsonNode value = node.path("value");
        if (value.isMissingNode() || value.isNull() || value.asString("").isBlank()) {
            return Field.missing();
        }
        String text = value.isTextual() ? value.asString("") : value.toString();
        try {
            return new Field<>(reader.apply(text.trim()), node.path("confidence").asDouble(0));
        } catch (RuntimeException e) {
            // the value itself may be personal data: the warning names the field and the rule, not the value
            warnings.add(name + ": not a valid value (" + rule(e) + ")");
            return Field.missing();
        }
    }

    private static String rule(RuntimeException e) {
        return switch (e) {
            case DateTimeParseException d -> "expected a date YYYY-MM-DD";
            case IllegalArgumentException i when i.getMessage() != null && i.getMessage().startsWith("No enum constant") ->
                    "not one of the allowed values";
            case IllegalArgumentException i -> stripValue(i.getMessage());
            default -> e.getClass().getSimpleName();
        };
    }

    /** Messages from {@link Money} quote the value; keep only the rule after it. */
    private static String stripValue(String message) {
        if (message == null) {
            return "invalid";
        }
        int quoted = message.lastIndexOf('\'');
        return quoted >= 0 ? message.substring(quoted + 1).trim() : message;
    }

    private static String text(String value) {
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value;
    }

    private static String constant(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static String currencyCode(String value) {
        return java.util.Currency.getInstance(value.trim().toUpperCase(Locale.ROOT)).getCurrencyCode();
    }
}
