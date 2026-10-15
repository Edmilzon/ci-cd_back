package com.space.cdci.projects;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ProjectSecretDtos {
	private ProjectSecretDtos() {}

	public record WriteSecretRequest(@NotNull @Size(min = 8, max = 4096) String value) {}
	public record SecretSummary(String name, String updatedBy, Instant updatedAt) {}
}
