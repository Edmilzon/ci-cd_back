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
	private final Set<String> permissionCodes;
	private String passwordHash;

	private CdciPrincipal(UUID id, String email, String fullName, boolean enabled, Set<String> roleCodes, Set<String> permissionCodes, String passwordHash) {
		this.id = id;
		this.email = email;
		this.fullName = fullName;
		this.enabled = enabled;
		this.roleCodes = Set.copyOf(roleCodes);
		this.permissionCodes = Set.copyOf(permissionCodes);
		this.passwordHash = passwordHash;
	}

	public UUID id() { return id; }
	public String email() { return email; }
	public String fullName() { return fullName; }
	public boolean enabled() { return enabled; }
	public Set<String> roleCodes() { return roleCodes; }
	public Set<String> permissionCodes() { return permissionCodes; }
	@Override public Collection<? extends GrantedAuthority> getAuthorities() {
		java.util.Set<GrantedAuthority> authorities = new java.util.HashSet<>();
		roleCodes.forEach(code -> authorities.add(new SimpleGrantedAuthority("ROLE_" + code)));
		permissionCodes.forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
		return Set.copyOf(authorities);
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
		Set<String> permissions = account.getRoles().stream().flatMap(role -> role.getPermissions().stream())
				.map(Permission::getCode).collect(Collectors.toUnmodifiableSet());
		return new CdciPrincipal(account.getId(), account.getEmail(), account.getFullName(),
				account.isEnabled() && account.isEmailVerified(), roleCodes, permissions, account.getPasswordHash());
	}
}
