package com.space.cdci.admin;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

@Component
public class SessionInvalidator {
	private final FindByIndexNameSessionRepository<? extends Session> sessions;

	public SessionInvalidator(FindByIndexNameSessionRepository<? extends Session> sessions) { this.sessions = sessions; }

	public void invalidate(String username) {
		invalidateExcept(username, null);
	}

	public void invalidateExcept(String username, String excludedSessionId) {
		for (Session session : sessions.findByPrincipalName(username).values()) {
			if (excludedSessionId == null || !excludedSessionId.equals(session.getId())) sessions.deleteById(session.getId());
		}
	}
}
