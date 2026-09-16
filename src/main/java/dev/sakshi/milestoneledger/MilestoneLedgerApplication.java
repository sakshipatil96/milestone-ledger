package dev.sakshi.milestoneledger;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class MilestoneLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MilestoneLedgerApplication.class, args);
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
