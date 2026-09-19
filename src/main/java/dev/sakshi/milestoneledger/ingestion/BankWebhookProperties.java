package dev.sakshi.milestoneledger.ingestion;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.bank.webhook")
public record BankWebhookProperties(
        @NotBlank @Size(max = 50) String source,
        @NotNull UUID projectId,
        @NotBlank @Size(max = 100) String accountReference,
        @NotBlank String signingSecret) {
}
