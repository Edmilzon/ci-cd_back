package com.space.cdci.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
	@ExceptionHandler(EmailAlreadyRegisteredException.class)
	public ResponseEntity<Map<String, Object>> duplicateEmail(EmailAlreadyRegisteredException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "EMAIL_ALREADY_REGISTERED", "message", exception.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> invalidRequest(MethodArgumentNotValidException exception) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (FieldError error : exception.getBindingResult().getFieldErrors()) {
			fields.putIfAbsent(error.getField(), error.getDefaultMessage() == null ? "Valor no válido." : error.getDefaultMessage());
		}
		return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED", "message", "Revisa los datos ingresados.", "fields", fields));
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, Object>> requestRejected(ResponseStatusException exception) {
		String message = exception.getReason() == null ? "No se pudo completar la solicitud." : exception.getReason();
		return ResponseEntity.status(exception.getStatusCode()).body(Map.of("code", "REQUEST_REJECTED", "message", message));
	}
}
