package com.space.cdci.identity;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
	private final IdentityService identityService;
	private final UserRepository users;
	private final PasswordChangeService passwordChangeService;
	private final PasswordResetService passwordResetService;
	private final EmailVerificationService emailVerificationService;

	public AuthController(IdentityService identityService, UserRepository users, PasswordChangeService passwordChangeService,
			PasswordResetService passwordResetService, EmailVerificationService emailVerificationService) {
		this.identityService = identityService;
		this.users = users;
		this.passwordChangeService = passwordChangeService;
		this.passwordResetService = passwordResetService;
		this.emailVerificationService = emailVerificationService;
	}

	@GetMapping("/csrf")
	public AuthDtos.CsrfResponse csrf(CsrfToken token) { return new AuthDtos.CsrfResponse(token.getToken()); }

	@PostMapping("/register")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
	public AuthDtos.RegisterResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
		return identityService.register(request, httpRequest);
	}

	@GetMapping("/email-verification/status")
	public AuthDtos.EmailVerificationStatusResponse emailVerificationStatus() {
		return new AuthDtos.EmailVerificationStatusResponse(emailVerificationService.isRequired());
	}

	@PostMapping("/email-verification/resend")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
	public AuthDtos.MessageResponse resendVerification(@Valid @RequestBody AuthDtos.EmailVerificationRequest request,
			jakarta.servlet.http.HttpServletRequest httpRequest) {
		return new AuthDtos.MessageResponse(emailVerificationService.resend(request, httpRequest));
	}

	@PostMapping("/email-verification/confirm")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
	public void confirmVerification(@Valid @RequestBody AuthDtos.EmailVerificationConfirmRequest request,
			jakarta.servlet.http.HttpServletRequest httpRequest) {
		emailVerificationService.confirm(request, httpRequest);
	}

	@GetMapping("/password-reset/status")
	public AuthDtos.PasswordResetStatusResponse passwordResetStatus() {
		return new AuthDtos.PasswordResetStatusResponse(passwordResetService.isEnabled());
	}

	@PostMapping("/password-reset/request")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
	public AuthDtos.MessageResponse requestPasswordReset(@Valid @RequestBody AuthDtos.PasswordResetRequest request,
			jakarta.servlet.http.HttpServletRequest httpRequest) {
		return new AuthDtos.MessageResponse(passwordResetService.requestReset(request, httpRequest));
	}

	@PostMapping("/password-reset/confirm")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
	public void confirmPasswordReset(@Valid @RequestBody AuthDtos.PasswordResetConfirmRequest request,
			jakarta.servlet.http.HttpServletRequest httpRequest) {
		passwordResetService.resetPassword(request, httpRequest);
	}

	@GetMapping("/me")
	public AuthDtos.UserResponse current(@AuthenticationPrincipal CdciPrincipal principal) {
		UserAccount user = users.findWithAccessById(principal.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
		return AuthDtos.UserResponse.from(user);
	}

	@PutMapping("/password")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
	public void changePassword(@AuthenticationPrincipal CdciPrincipal principal,
			@Valid @RequestBody AuthDtos.PasswordChangeRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
		passwordChangeService.change(principal, request, httpRequest);
	}
}
