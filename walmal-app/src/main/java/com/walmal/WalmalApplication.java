package com.walmal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan("com.walmal")
@EnableJpaRepositories(basePackages = {
        "com.walmal.auth.infrastructure",
        "com.walmal.product.infrastructure",
        "com.walmal.inventory.infrastructure",
        "com.walmal.order.infrastructure",
        "com.walmal.pos.infrastructure",
        "com.walmal.warehouse.infrastructure",
        "com.walmal.notification.infrastructure"
})
public class WalmalApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalmalApplication.class, args);
    }
}
