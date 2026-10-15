package com.space.cdci.identity;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select token from EmailVerificationToken token join fetch token.user where token.tokenHash = :hash")
	Optional<EmailVerificationToken> findByTokenHashForUpdate(@Param("hash") String hash);

	@Modifying
	@Query("delete from EmailVerificationToken token where token.user.id = :userId")
	int deleteAllForUser(@Param("userId") UUID userId);

	@Modifying
	@Query("delete from EmailVerificationToken token where token.expiresAt < :cutoff or token.consumedAt < :cutoff")
	int purgeBefore(@Param("cutoff") Instant cutoff);
}
