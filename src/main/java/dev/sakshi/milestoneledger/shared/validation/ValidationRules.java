package dev.sakshi.milestoneledger.shared.validation;

import dev.sakshi.milestoneledger.shared.web.ApiException;
import org.springframework.http.HttpStatus;

public final class ValidationRules {
    private ValidationRules() {
    }

    public static long positivePaise(String value) {
        if (value == null || !value.matches("[0-9]+")) {
            throw invalid();
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw invalid();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    public static long addPaise(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw invalid();
        }
    }

    public static String exactReference(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw invalid();
        }
        return value;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request.");
    }
}
