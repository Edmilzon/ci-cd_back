package com.space.cdci.identity;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdentityBootstrap {
	@Bean
	ApplicationRunner seedIdentity(IdentityBootstrapService bootstrapService) {
		return args -> bootstrapService.seedIdentity();
	}
}
