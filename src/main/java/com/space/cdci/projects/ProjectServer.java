package com.space.cdci.projects;

import com.space.cdci.identity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_servers", uniqueConstraints = @UniqueConstraint(name = "uq_project_servers_project_name", columnNames = {"project_id", "name"}))
public class ProjectServer {
	@Id private UUID id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id") private Project project;
	@Column(nullable = false, length = 100) private String name;
	@Column(nullable = false, length = 253) private String host;
	@Column(nullable = false) private int port;
	@Column(nullable = false, length = 100) private String username;
	@Column(name = "password_ciphertext", nullable = false, columnDefinition = "text") private String passwordCiphertext;
	@Column(name = "last_status", nullable = false, length = 20) private String lastStatus;
	@Column(name = "last_connected_at") private Instant lastConnectedAt;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_user_id") private UserAccount createdBy;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "updated_by_user_id") private UserAccount updatedBy;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected ProjectServer() {}

	public ProjectServer(UUID id, Project project, String name, String host, int port, String username,
			String passwordCiphertext, UserAccount actor) {
		this.id = id;
		this.project = project;
		this.name = name;
		this.host = host;
		this.port = port;
		this.username = username;
		this.passwordCiphertext = passwordCiphertext;
		this.lastStatus = "UNKNOWN";
		this.createdBy = actor;
		this.updatedBy = actor;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	public void update(String name, String host, int port, String username, String passwordCiphertext, UserAccount actor) {
		this.name = name;
		this.host = host;
		this.port = port;
		this.username = username;
		this.passwordCiphertext = passwordCiphertext;
		this.updatedBy = actor;
		this.updatedAt = Instant.now();
	}

	public void markConnected() {
		this.lastStatus = "CONNECTED";
		this.lastConnectedAt = Instant.now();
	}

	public void markFailed() {
		this.lastStatus = "FAILED";
	}

	public UUID getId() { return id; }
	public Project getProject() { return project; }
	public String getName() { return name; }
	public String getHost() { return host; }
	public int getPort() { return port; }
	public String getUsername() { return username; }
	public String getPasswordCiphertext() { return passwordCiphertext; }
	public String getLastStatus() { return lastStatus; }
	public Instant getLastConnectedAt() { return lastConnectedAt; }
	public UserAccount getCreatedBy() { return createdBy; }
	public UserAccount getUpdatedBy() { return updatedBy; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
