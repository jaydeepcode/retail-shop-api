package com.mangle.retailshopapp.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * An injectable clock.
 *
 * <p>design-api.md §5.1 names {@code Clock} as a collaborator of
 * {@code RechargeSettlementService}, and the reason is testability rather than
 * taste: the financial year, the 30-day credit warning and the day summary are
 * all "what is true right now" questions, and a test that cannot move the clock
 * either cannot assert them or asserts them against the day it happens to run.
 * {@code LocalDateTime.now()} scattered through services is what makes a 31-March
 * boundary bug unreproducible.
 *
 * <p>System default zone, not UTC. Every date in this application is a local
 * shop date: the financial year runs 1 April to 31 March in Indian local time,
 * the day summary is the shop's trading day, and {@code rc_setting} stores no
 * timezone. Using UTC here would shift the day boundary by 5½ hours and make
 * every evening sale after 18:30 fall on the next day's summary — which is
 * exactly the shift that makes the MCP server's rendered dates read 5½ hours
 * early (log §14, and the S3 brief repeats the caution).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
