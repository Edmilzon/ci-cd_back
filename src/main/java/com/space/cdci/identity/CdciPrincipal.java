package com.space.cdci.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class CdciPrincipal implements UserDetails, CredentialsContainer, Serializable, java.security.Principal {
	@Serial private static final long serialVersionUID = 1L;
	private final UUID id;
	private final String email;
	private final String fullName;
	private final boolean enabled;
	private final Set<String> roleCodes;
	private String passwordHash;

	private CdciPrincipal(UUID id, String email, String fullName, boolean enabled, Set<String> roleCodes, String passwordHash) {
		this.id = id;
		this.email = email;
		this.fullName = fullName;
		this.enabled = enabled;
		this.roleCodes = Set.copyOf(roleCodes);
		this.passwordHash = passwordHash;
	}

	public UUID id() { return id; }
	public String email() { return email; }
	public String fullName() { return fullName; }
	public boolean enabled() { return enabled; }
	public Set<String> roleCodes() { return roleCodes; }
	@Override public Collection<? extends GrantedAuthority> getAuthorities() {
		return roleCodes.stream().map(code -> new SimpleGrantedAuthority("ROLE_" + code)).toList();
	}
	@Override public String getPassword() { return passwordHash; }
	@Override public String getUsername() { return email; }
	@Override public String getName() { return email; }
	@Override public boolean isAccountNonExpired() { return true; }
	@Override public boolean isAccountNonLocked() { return enabled; }
	@Override public boolean isCredentialsNonExpired() { return true; }
	@Override public boolean isEnabled() { return enabled; }
	@Override public void eraseCredentials() { passwordHash = null; }

	public static CdciPrincipal from(UserAccount account) {
		Set<String> roleCodes = account.getRoles().stream().map(Role::getCode).collect(Collectors.toUnmodifiableSet());
		return new CdciPrincipal(account.getId(), account.getEmail(), account.getFullName(), account.isEnabled(), roleCodes, account.getPasswordHash());
	}
}
