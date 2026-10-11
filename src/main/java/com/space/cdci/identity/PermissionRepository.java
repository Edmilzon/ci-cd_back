package com.space.cdci.identity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
	java.util.Optional<Permission> findByCode(String code);
	List<Permission> findAllByCodeIn(Collection<String> codes);
	List<Permission> findAllByOrderByCodeAsc();
}
