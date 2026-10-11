package com.space.cdci.identity;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CdciUserDetailsService implements UserDetailsService {
	private final UserRepository users;

	public CdciUserDetailsService(UserRepository users) { this.users = users; }

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		return users.findByEmailIgnoreCase(email.trim().toLowerCase())
				.map(CdciPrincipal::from)
				.orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
	}
}
