package com.crisinis.fileuploadservice.support;

/** A model's answer for a complete policy, as the extraction prompt asks for it. */
public final class SamplePolicy {

    private SamplePolicy() {
    }

    public static final String JSON = """
            {
              "insurer": {"value": "Example Insurance Ltd", "confidence": 0.98},
              "policyNumber": {"value": "HPC-0042-7781", "confidence": 0.97},
              "policyType": {"value": "home and contents", "confidence": 0.9},
              "holder": {
                "name": {"value": "Jane Citizen", "confidence": 0.96},
                "dateOfBirth": {"value": "1984-07-21", "confidence": 0.8},
                "address": {"value": "1 Example Street, Sydney NSW 2000", "confidence": 0.9}
              },
              "insuredPersons": [{"name": {"value": "John Citizen", "confidence": 0.9}, "dateOfBirth": {"value": null, "confidence": 0}}],
              "startDate": {"value": "2026-07-01", "confidence": 0.95},
              "endDate": {"value": "2027-06-30", "confidence": 0.95},
              "currency": {"value": "aud", "confidence": 0.99},
              "premium": {"value": "1,842.60", "confidence": 0.94},
              "premiumFrequency": {"value": "monthly", "confidence": 0.9},
              "sumInsured": {"value": 850000, "confidence": 0.92},
              "excess": {"value": "500", "confidence": 1.4}
            }""";
}
