package com.space.cdci.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserAccount, UUID> {
	@EntityGraph(attributePaths = {"roles", "roles.permissions"})
	Optional<UserAccount> findByEmailIgnoreCase(String email);

	Page<UserAccount> findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCase(String email, String fullName, Pageable pageable);

	long countByEnabledTrueAndRoles_Code(String roleCode);
	long countByRoles_Code(String roleCode);
	boolean existsByRoles_Code(String roleCode);

	@EntityGraph(attributePaths = {"roles", "roles.permissions"})
	@Query("select u from UserAccount u where u.id = :id")
	java.util.Optional<UserAccount> findWithAccessById(@Param("id") UUID id);

	@EntityGraph(attributePaths = {"roles", "roles.permissions"})
	@Query("select u from UserAccount u where u.email = :email")
	java.util.Optional<UserAccount> findWithAccessByEmail(@Param("email") String email);

	@Query("select distinct u.email from UserAccount u join u.roles r where r.code = :roleCode")
	java.util.List<String> findEmailsByRoleCode(@Param("roleCode") String roleCode);

	@Query("select u from UserAccount u where lower(u.email) like lower(concat('%', :search, '%')) or lower(u.fullName) like lower(concat('%', :search, '%'))")
	Page<UserAccount> search(@Param("search") String search, Pageable pageable);

	@Query("select u from UserAccount u")
	Page<UserAccount> searchAll(Pageable pageable);
}
