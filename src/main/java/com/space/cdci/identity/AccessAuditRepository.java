package com.space.cdci.identity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessAuditRepository extends JpaRepository<AccessAuditEvent, UUID> {}
