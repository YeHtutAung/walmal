package com.walmal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication
@EntityScan("com.walmal")
public class WalmalApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalmalApplication.class, args);
    }
}
