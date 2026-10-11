package com.space.cdci.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
	@Id
	private UUID id;

	@Column(nullable = false, unique = true, length = 254)
	private String email;

	@Column(name = "full_name", nullable = false, length = 80)
	private String fullName;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
	private Set<Role> roles = new HashSet<>();

	protected UserAccount() {}

	public UserAccount(String email, String fullName, String passwordHash, Set<Role> roles) {
		this.id = UUID.randomUUID();
		this.email = email;
		this.fullName = fullName;
		this.passwordHash = passwordHash;
		this.roles = new HashSet<>(roles);
		this.enabled = true;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	public UUID getId() { return id; }
	public String getEmail() { return email; }
	public String getFullName() { return fullName; }
	public String getPasswordHash() { return passwordHash; }
	public boolean isEnabled() { return enabled; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
	public Set<Role> getRoles() { return roles; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; this.updatedAt = Instant.now(); }
	public void setRoles(Set<Role> roles) { this.roles = new HashSet<>(roles); this.updatedAt = Instant.now(); }
	public void changePasswordHash(String passwordHash) { this.passwordHash = passwordHash; this.updatedAt = Instant.now(); }
}
