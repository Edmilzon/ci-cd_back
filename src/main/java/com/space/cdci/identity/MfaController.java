package com.space.cdci.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth/mfa")
public class MfaController {
	private final MfaService mfa;

	public MfaController(MfaService mfa) { this.mfa = mfa; }

	@PostMapping("/login")
	public Map<String, Boolean> completeLogin(@Valid @RequestBody AuthDtos.MfaLoginRequest request, HttpServletRequest httpRequest) {
		HttpSession session = httpRequest.getSession(false);
		MfaService.PendingChallenge pending = requirePending(session, false);
		UUID userId = mfa.verifyLogin(request.code(), httpRequest.getRemoteAddr(), pending.userId());
		Authentication authentication = authentication(mfa.principal(userId));
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		session.removeAttribute(MfaService.PENDING_USER_ATTRIBUTE);
		session.removeAttribute(MfaService.PENDING_AT_ATTRIBUTE);
		session.removeAttribute(MfaService.PENDING_SETUP_ATTRIBUTE);
		session.setAttribute(MfaService.AUTHENTICATED_AT_ATTRIBUTE, Instant.now().toEpochMilli());
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		return Map.of("authenticated", true);
	}

	@GetMapping("/status")
	public AuthDtos.MfaStatusResponse status(@AuthenticationPrincipal CdciPrincipal principal) {
		return mfa.status(principal.id());
	}

	@PostMapping("/setup")
	public AuthDtos.MfaSetupResponse setup(@AuthenticationPrincipal CdciPrincipal principal,
			@Valid @RequestBody AuthDtos.MfaSetupRequest request, HttpServletRequest httpRequest) {
		UUID userId;
		if (principal != null) {
			userId = principal.id();
		} else {
			MfaService.PendingChallenge pending = requirePending(httpRequest.getSession(false), true);
			userId = pending.userId();
		}
		return mfa.startEnrollment(userId, request.currentPassword());
	}

	@PostMapping("/confirm")
	public AuthDtos.MfaRecoveryCodesResponse confirm(@AuthenticationPrincipal CdciPrincipal principal,
			@Valid @RequestBody AuthDtos.MfaConfirmRequest request, HttpServletRequest httpRequest) {
		MfaService.PendingChallenge pending = principal == null ? requirePending(httpRequest.getSession(false), true) : null;
		UUID userId = principal == null ? pending.userId() : principal.id();
		var codes = mfa.confirmEnrollment(userId, request.code());
		if (pending != null) completeForcedEnrollment(httpRequest.getSession(false), pending.userId());
		return new AuthDtos.MfaRecoveryCodesResponse(true, codes);
	}

	@PostMapping("/recovery-codes")
	public AuthDtos.MfaRecoveryCodesResponse regenerate(@AuthenticationPrincipal CdciPrincipal principal,
			@Valid @RequestBody AuthDtos.MfaRecoveryCodesRequest request) {
		return new AuthDtos.MfaRecoveryCodesResponse(true, mfa.regenerateRecoveryCodes(principal, request));
	}

	@PostMapping("/disable")
	@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
	public void disable(@AuthenticationPrincipal CdciPrincipal principal, @Valid @RequestBody AuthDtos.MfaDisableRequest request,
			HttpServletRequest httpRequest) {
		mfa.disable(principal, request, httpRequest);
	}

	private MfaService.PendingChallenge requirePending(HttpSession session, boolean requireSetup) {
		MfaService.PendingChallenge pending = mfa.pendingChallenge(session);
		if (pending == null || (requireSetup && !pending.setupRequired()) || (!requireSetup && pending.setupRequired())) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "El desafío MFA venció. Inicia sesión nuevamente.");
		}
		return pending;
	}

	private void completeForcedEnrollment(HttpSession session, UUID userId) {
		if (session == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "La sesión de configuración MFA venció.");
		MfaService.PendingChallenge pending = requirePending(session, true);
		if (!pending.userId().equals(userId)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "La sesión de configuración MFA venció.");
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication(mfa.principal(userId)));
		SecurityContextHolder.setContext(context);
		session.removeAttribute(MfaService.PENDING_USER_ATTRIBUTE);
		session.removeAttribute(MfaService.PENDING_AT_ATTRIBUTE);
		session.removeAttribute(MfaService.PENDING_SETUP_ATTRIBUTE);
		session.setAttribute(MfaService.AUTHENTICATED_AT_ATTRIBUTE, Instant.now().toEpochMilli());
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
	}

	private static Authentication authentication(CdciPrincipal principal) {
		principal.eraseCredentials();
		return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
	}
}
