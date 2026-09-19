package dev.sakshi.milestoneledger.shared.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> api(ApiException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ApiErrorResponse> validation(HttpServletRequest request) {
        return response(org.springframework.http.HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> forbidden(HttpServletRequest request) {
        return response(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied.", request);
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiErrorResponse> database(HttpServletRequest request) {
        return response(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "A required dependency is unavailable.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> integrity(DataIntegrityViolationException exception, HttpServletRequest request) {
        if (hasCauseContaining(exception, "milestone_certification_reference_unique")) {
            return response(org.springframework.http.HttpStatus.CONFLICT, "CERTIFICATION_REFERENCE_CONFLICT",
                    "Certification reference is already in use.", request);
        }
        return response(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "A required dependency is unavailable.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(HttpServletRequest request) {
        return response(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred.", request);
    }

    static ResponseEntity<ApiErrorResponse> response(
            org.springframework.http.HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                new ApiErrorResponse.Error(code, message, CorrelationIdFilter.requestId(request))));
    }

    private static boolean hasCauseContaining(Throwable exception, String value) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(value)) return true;
        }
        return false;
    }

}
