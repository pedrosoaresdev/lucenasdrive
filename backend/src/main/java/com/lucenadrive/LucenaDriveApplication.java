package com.lucenadrive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LucenaDriveApplication {

    public static void main(String[] args) {
        SpringApplication.run(LucenaDriveApplication.class, args);
    }
}
