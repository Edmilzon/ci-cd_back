package com.space.cdci.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PipelineYamlValidatorTest {
	private final PipelineYamlValidator validator = new PipelineYamlValidator();

	@Test
	void acceptsManualPipelineAndCountsWork() {
		var result = validator.validate("""
				name: Release
				triggers: [manual]
				jobs:
				  build:
				    image: eclipse-temurin:21-jdk
				    network: none
				    steps:
				      - name: Compile
				        run: ./gradlew build
				  deploy:
				    environment: staging
				    steps:
				      - run: ./deploy.sh
				""");

		assertTrue(result.valid(), () -> String.join("; ", result.errors()));
		assertEquals("Release", result.name());
		assertEquals(2, result.jobs());
		assertEquals(2, result.steps());
	}

	@Test
	void rejectsAutomaticTriggersAndReservedSecrets() {
		var triggerResult = validator.validate("""
				name: Release
				triggers: [push]
				jobs:
				  build:
				    steps:
				      - run: ./gradlew build
				""");
		var secretResult = validator.validate("""
				name: Release
				triggers: [manual]
				jobs:
				  build:
				    secrets: [CDCI_RUNNER_TOKEN]
				    steps:
				      - run: ./gradlew build
				""");

		assertFalse(triggerResult.valid());
		assertTrue(triggerResult.errors().stream().anyMatch(message -> message.contains("Solo se admite el disparador manual")));
		assertFalse(secretResult.valid());
		assertTrue(secretResult.errors().stream().anyMatch(message -> message.contains("nombre válido en mayúsculas")));
	}

	@Test
	void returnsOnlySecretsDeclaredForRollbackEnvironment() {
		String yaml = """
				name: Release
				triggers: [manual]
				jobs:
				  build:
				    secrets: [NPM_TOKEN]
				    steps:
				      - run: npm ci
				  deploy:
				    environment: Production
				    secrets: [DEPLOY_TOKEN]
				    steps:
				      - run: ./deploy.sh
				""";

		assertEquals(List.of("NPM_TOKEN", "DEPLOY_TOKEN"), validator.referencedSecrets(yaml));
		assertEquals(List.of("DEPLOY_TOKEN"), validator.referencedSecrets(yaml, "production"));
	}
}
