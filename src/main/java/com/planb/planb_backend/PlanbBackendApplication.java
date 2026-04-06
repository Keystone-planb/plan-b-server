package com.planb.planb_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class PlanbBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanbBackendApplication.class, args);
    }

}
