package dev.sakshi.milestoneledger.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AppSecurityPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "app.security.certifier-password-hash=$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu",
                    "app.security.accounts-password-hash=$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu",
                    "app.security.manager-password-hash=$2y$10$NdIazdiUe88vtpMK8FnE.O1BKli2ZuCQbnu/zoiv2xuAGD/JnxBqu");

    @Test
    void startsWithValidBcryptHashes() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsMalformedBcryptHashWithoutEchoingIt() {
        String invalidHash = "definitely-not-a-bcrypt-hash";
        contextRunner.withPropertyValues("app.security.certifier-password-hash=" + invalidHash)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).doesNotContain(invalidHash);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppSecurityProperties.class)
    static class PropertiesConfiguration {
    }
}
