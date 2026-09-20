package dev.sakshi.milestoneledger;

import java.time.Clock;

import dev.sakshi.milestoneledger.ingestion.BankWebhookProperties;
import dev.sakshi.milestoneledger.setup.SetupProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({SetupProperties.class, BankWebhookProperties.class})
public class MilestoneLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MilestoneLedgerApplication.class, args);
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
