package com.space.cdci.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserAccount, UUID> {
	@EntityGraph(attributePaths = "roles")
	Optional<UserAccount> findByEmailIgnoreCase(String email);

	@org.springframework.data.jpa.repository.EntityGraph(attributePaths = "roles")
	java.util.Optional<UserAccount> findWithRolesById(UUID id);

	Page<UserAccount> findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCase(String email, String fullName, Pageable pageable);

	long countByEnabledTrueAndRoles_Code(String roleCode);
}
