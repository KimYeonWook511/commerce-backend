package com.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CommerceBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommerceBackendApplication.class, args);
	}

}
