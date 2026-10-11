package com.space.cdci.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "permissions")
public class Permission {
	@Id
	private UUID id;

	@Column(nullable = false, unique = true, length = 64)
	private String code;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 240)
	private String description;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Permission() {}

	public Permission(String code, String name, String description) {
		this.id = UUID.randomUUID();
		this.code = code;
		this.name = name;
		this.description = description;
		this.createdAt = Instant.now();
	}

	public UUID getId() { return id; }
	public String getCode() { return code; }
	public String getName() { return name; }
	public String getDescription() { return description; }
	public Instant getCreatedAt() { return createdAt; }
}
