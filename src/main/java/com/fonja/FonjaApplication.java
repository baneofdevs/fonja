package com.fonja;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling drives the campaign expiry sweep — see CampaignExpiryJob. Async is for
// NotificationService: emails go out on the application task executor after the publishing
// transaction commits, so no request thread waits on SMTP.
//
// VIA_DTO pins the JSON shape of every paged response to Spring Data's PagedModel:
// {content: [...], page: {size, number, totalElements, totalPages}}. The default serialises
// PageImpl as-is, which Spring Data explicitly does not promise to keep stable between
// versions — not something a frontend should be built against.
@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class FonjaApplication {
    public static void main(String[] args) {
        SpringApplication.run(FonjaApplication.class, args);
    }
}
