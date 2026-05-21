package com.walmal.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "walmal.notification")
public class NotificationProperties {

    private String staffDistributionEmail = "staff@walmal.internal";

    public String getStaffDistributionEmail() { return staffDistributionEmail; }
    public void setStaffDistributionEmail(String staffDistributionEmail) {
        this.staffDistributionEmail = staffDistributionEmail;
    }
}
