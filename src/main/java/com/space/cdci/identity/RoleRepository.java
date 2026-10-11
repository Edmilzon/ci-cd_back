package com.space.cdci.identity;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleRepository extends JpaRepository<Role, UUID> {
	Optional<Role> findByCode(String code);
	List<Role> findAllByCodeIn(Collection<String> codes);
	boolean existsByCode(String code);

	@Query("select distinct r from Role r left join fetch r.permissions order by r.code")
	List<Role> findAllWithPermissions();

	@Query("select distinct r from Role r left join fetch r.permissions where r.code = :code")
	Optional<Role> findWithPermissionsByCode(@Param("code") String code);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Role r where r.code = :code")
	Optional<Role> lockByCode(@Param("code") String code);
}
