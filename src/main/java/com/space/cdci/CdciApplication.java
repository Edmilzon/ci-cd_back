package com.space.cdci;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CdciApplication {

	public static void main(String[] args) {
		SpringApplication.run(CdciApplication.class, args);
	}

}
