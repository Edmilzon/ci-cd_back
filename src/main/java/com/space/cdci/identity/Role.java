package com.space.cdci.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "roles")
public class Role {
	@Id
	private UUID id;

	@Column(nullable = false, unique = true, length = 48)
	private String code;

	@Column(nullable = false, length = 80)
	private String name;

	@Column(nullable = false, length = 240)
	private String description;

	@Column(name = "system_managed", nullable = false)
	private boolean systemManaged;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Role() {}

	public Role(String code, String name, String description, boolean systemManaged) {
		this.id = UUID.randomUUID();
		this.code = code;
		this.name = name;
		this.description = description;
		this.systemManaged = systemManaged;
		this.createdAt = Instant.now();
	}

	public UUID getId() { return id; }
	public String getCode() { return code; }
	public String getName() { return name; }
	public String getDescription() { return description; }
	public boolean isSystemManaged() { return systemManaged; }
	public Instant getCreatedAt() { return createdAt; }
	public void update(String name, String description) { this.name = name; this.description = description; }
}
