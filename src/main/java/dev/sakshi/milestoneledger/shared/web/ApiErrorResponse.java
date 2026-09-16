package dev.sakshi.milestoneledger.shared.web;

public record ApiErrorResponse(Error error) {
    public record Error(String code, String message, String requestId) {
    }
}
