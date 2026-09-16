package dev.sakshi.milestoneledger.setup;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.setup")
public record SetupProperties(@NotNull UUID projectId) {
}
