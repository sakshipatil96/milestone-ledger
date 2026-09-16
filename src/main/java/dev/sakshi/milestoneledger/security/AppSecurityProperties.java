package dev.sakshi.milestoneledger.security;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.security")
public record AppSecurityProperties(
        @NotBlank String certifierPassword,
        @NotBlank String accountsPassword,
        @NotBlank String managerPassword) {
}
