package com.space.cdci.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {
	@Test
	void matchesRfc6238Sha1VectorWithSixDigits() {
		String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
		assertEquals("287082", Totp.codeAt(secret, Instant.ofEpochSecond(59)));
		assertEquals(1L, Totp.matchingStep(secret, "287082", Instant.ofEpochSecond(59), -1).orElseThrow());
	}

	@Test
	void rejectsAPreviouslyAcceptedTimeStep() {
		String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
		assertTrue(Totp.matchingStep(secret, "287082", Instant.ofEpochSecond(59), 1).isEmpty());
	}

	@Test
	void acceptsOneStepOfClockDrift() {
		String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
		assertTrue(Totp.matchingStep(secret, Totp.codeAt(secret, Instant.ofEpochSecond(59)), Instant.ofEpochSecond(89), -1).isPresent());
	}
}
