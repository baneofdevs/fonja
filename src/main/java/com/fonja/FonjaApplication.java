package com.fonja;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling drives the campaign expiry sweep — see CampaignExpiryJob.
@SpringBootApplication
@EnableScheduling
public class FonjaApplication {
    public static void main(String[] args) {
        SpringApplication.run(FonjaApplication.class, args);
    }
}
