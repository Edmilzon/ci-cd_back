package com.space.cdci.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;

class SessionInvalidatorTest {
	@SuppressWarnings("unchecked")
	@Test
	void retainsTheCurrentSessionAndDeletesEveryOtherSessionForAUser() {
		FindByIndexNameSessionRepository<Session> repository = mock(FindByIndexNameSessionRepository.class);
		Session current = new MapSession("current-session");
		Session other = new MapSession("other-session");
		when(repository.findByPrincipalName("user@example.invalid")).thenReturn(Map.of(current.getId(), current, other.getId(), other));

		new SessionInvalidator(repository).invalidateExcept("user@example.invalid", "current-session");

		verify(repository).deleteById("other-session");
		verify(repository, never()).deleteById("current-session");
	}

	@SuppressWarnings("unchecked")
	@Test
	void invalidatesAllSessionsWhenNoSessionIsExcluded() {
		FindByIndexNameSessionRepository<Session> repository = mock(FindByIndexNameSessionRepository.class);
		Session first = new MapSession("first-session");
		Session second = new MapSession("second-session");
		when(repository.findByPrincipalName("user@example.invalid")).thenReturn(Map.of(first.getId(), first, second.getId(), second));

		new SessionInvalidator(repository).invalidate("user@example.invalid");

		verify(repository).deleteById("first-session");
		verify(repository).deleteById("second-session");
	}
}
