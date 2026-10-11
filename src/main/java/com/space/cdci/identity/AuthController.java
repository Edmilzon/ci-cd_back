package com.space.cdci.identity;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
	private final IdentityService identityService;
	private final UserRepository users;

	public AuthController(IdentityService identityService, UserRepository users) {
		this.identityService = identityService;
		this.users = users;
	}

	@GetMapping("/csrf")
	public AuthDtos.CsrfResponse csrf(CsrfToken token) { return new AuthDtos.CsrfResponse(token.getToken()); }

	@PostMapping("/register")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
	public AuthDtos.UserResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
		return identityService.register(request);
	}

	@GetMapping("/me")
	public AuthDtos.UserResponse current(@AuthenticationPrincipal CdciPrincipal principal) {
		UserAccount user = users.findWithRolesById(principal.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
		return AuthDtos.UserResponse.from(user);
	}
}
