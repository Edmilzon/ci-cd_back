package com.space.cdci.integrations.github;

import com.space.cdci.identity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "github_connections")
public class GitHubConnection {
	@Id private UUID id;
	@OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", unique = true) private UserAccount user;
	@Column(name = "github_user_id", nullable = false) private long githubUserId;
	@Column(name = "github_login", nullable = false, length = 39) private String githubLogin;
	@Column(name = "access_token_ciphertext", nullable = false, columnDefinition = "text") private String accessTokenCiphertext;
	@Column(name = "refresh_token_ciphertext", columnDefinition = "text") private String refreshTokenCiphertext;
	@Column(name = "access_token_expires_at") private Instant accessTokenExpiresAt;
	@Column(name = "refresh_token_expires_at") private Instant refreshTokenExpiresAt;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "updated_at", nullable = false) private Instant updatedAt;

	protected GitHubConnection() {}

	public GitHubConnection(UserAccount user, long githubUserId, String githubLogin, String accessTokenCiphertext,
			String refreshTokenCiphertext, Instant accessTokenExpiresAt, Instant refreshTokenExpiresAt) {
		this.id = UUID.randomUUID();
		this.user = user;
		this.githubUserId = githubUserId;
		this.githubLogin = githubLogin;
		this.accessTokenCiphertext = accessTokenCiphertext;
		this.refreshTokenCiphertext = refreshTokenCiphertext;
		this.accessTokenExpiresAt = accessTokenExpiresAt;
		this.refreshTokenExpiresAt = refreshTokenExpiresAt;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	public UUID getId() { return id; }
	public UserAccount getUser() { return user; }
	public long getGithubUserId() { return githubUserId; }
	public String getGithubLogin() { return githubLogin; }
	public String getAccessTokenCiphertext() { return accessTokenCiphertext; }
	public String getRefreshTokenCiphertext() { return refreshTokenCiphertext; }
	public Instant getAccessTokenExpiresAt() { return accessTokenExpiresAt; }
	public Instant getRefreshTokenExpiresAt() { return refreshTokenExpiresAt; }
	public Instant getCreatedAt() { return createdAt; }
	public void update(long githubUserId, String login, String accessTokenCiphertext, String refreshTokenCiphertext, Instant accessExpiresAt, Instant refreshExpiresAt) {
		this.githubUserId = githubUserId;
		this.githubLogin = login;
		this.accessTokenCiphertext = accessTokenCiphertext;
		this.refreshTokenCiphertext = refreshTokenCiphertext;
		this.accessTokenExpiresAt = accessExpiresAt;
		this.refreshTokenExpiresAt = refreshExpiresAt;
		this.updatedAt = Instant.now();
	}
}
