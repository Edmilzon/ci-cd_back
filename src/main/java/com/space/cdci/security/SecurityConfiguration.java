package com.space.cdci.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
	@Bean
	PasswordEncoder passwordEncoder() {
		return new Argon2PasswordEncoder(16, 32, 1, 65536, 3);
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(@Value("${cdci.frontend-origin}") String frontendOrigin) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(List.of(frontendOrigin));
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Accept", "Authorization", "Content-Type", "X-XSRF-TOKEN"));
		cors.setExposedHeaders(List.of("Location"));
		cors.setAllowCredentials(true);
		cors.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);
		return source;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
		http
			.cors(Customizer.withDefaults())
			.csrf(csrf -> {
				csrf.spa();
				csrf.ignoringRequestMatchers("/api/runner/**");
			})
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.GET, "/api/health/live", "/api/health/ready").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
				.requestMatchers("/api/runner/**").permitAll()
				.requestMatchers("/api/auth/**").authenticated()
				.requestMatchers("/api/admin/**").authenticated()
				.requestMatchers("/api/projects/**", "/api/integrations/github/**", "/api/dashboard").authenticated()
				.anyRequest().denyAll())
			.formLogin(form -> form
				.loginProcessingUrl("/api/auth/login")
				.usernameParameter("email")
				.passwordParameter("password")
				.successHandler(loginSuccessHandler(objectMapper))
				.failureHandler(loginFailureHandler(objectMapper)))
			.logout(logout -> logout
				.logoutUrl("/api/auth/logout")
				.clearAuthentication(true)
				.invalidateHttpSession(true)
				.deleteCookies("CDCI_SESSION")
				.logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value())))
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint((request, response, exception) -> writeError(objectMapper, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Inicia sesión para continuar."))
				.accessDeniedHandler((request, response, exception) -> writeError(objectMapper, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "No tienes permiso para realizar esta acción.")));
		return http.build();
	}

	private AuthenticationSuccessHandler loginSuccessHandler(ObjectMapper mapper) {
		return (request, response, authentication) -> {
			response.setStatus(HttpStatus.OK.value());
			response.setContentType("application/json");
			response.setCharacterEncoding("UTF-8");
			mapper.writeValue(response.getOutputStream(), java.util.Map.of("authenticated", true));
		};
	}

	private AuthenticationFailureHandler loginFailureHandler(ObjectMapper mapper) {
		return (request, response, exception) -> writeError(mapper, response, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Correo o contraseña incorrectos.");
	}

	private void writeError(ObjectMapper mapper, jakarta.servlet.http.HttpServletResponse response,
			HttpStatus status, String code, String message) throws java.io.IOException {
		response.setStatus(status.value());
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		mapper.writeValue(response.getOutputStream(), java.util.Map.of("code", code, "message", message));
	}
}
