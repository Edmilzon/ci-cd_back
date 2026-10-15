package com.space.cdci.ops;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {
	private final JdbcTemplate jdbc;

	public HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@GetMapping("/live")
	public ResponseEntity<Probe> live() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Probe("UP", Map.of("application", "UP")));
	}

	@GetMapping("/ready")
	public ResponseEntity<Probe> ready() {
		try {
			Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
			if (result != null && result == 1) {
				return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Probe("UP", Map.of("database", "UP")));
			}
		} catch (DataAccessException ignored) {
			// The probe deliberately returns a generic status and never exposes database details.
		}
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
				.body(new Probe("DOWN", Map.of("database", "DOWN")));
	}

	public record Probe(String status, Map<String, String> components) {}
}
