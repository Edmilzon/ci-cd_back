package com.space.cdci.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class IdentityServiceTest {
	@Mock private UserRepository users;
	@Mock private RoleRepository roles;
	@Mock private PasswordEncoder passwordEncoder;
	@Mock private EmailVerificationService emailVerification;
	@Mock private HttpServletRequest httpRequest;

	@Test
	void requiredVerificationReturnsNeutralRegistrationResponseForExistingEmail() {
		when(emailVerification.isRequired()).thenReturn(true);
		when(users.findByEmailIgnoreCase("person@example.com"))
				.thenReturn(Optional.of(new UserAccount("person@example.com", "Existing Person", "hash", Set.of())));
		IdentityService service = new IdentityService(users, roles, passwordEncoder, emailVerification, true);

		AuthDtos.RegisterResponse response = service.register(new AuthDtos.RegisterRequest(
				"Person@Example.com", "New Person", "a-long-password-value"), httpRequest);

		assertTrue(response.verificationRequired());
		assertFalse(response.emailVerified());
		verify(emailVerification).guardRegistration("person@example.com", httpRequest);
	}
}
