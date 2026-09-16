package dev.sakshi.milestoneledger.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.security")
public record AppSecurityProperties(
        @NotBlank @Pattern(regexp = "\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}") String certifierPasswordHash,
        @NotBlank @Pattern(regexp = "\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}") String accountsPasswordHash,
        @NotBlank @Pattern(regexp = "\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}") String managerPasswordHash) {
}
