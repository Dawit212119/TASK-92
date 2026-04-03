package com.civicworks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CivicWorksApplication {
    public static void main(String[] args) {
        SpringApplication.run(CivicWorksApplication.class, args);
    }
}
