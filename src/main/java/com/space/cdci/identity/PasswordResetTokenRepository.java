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

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select token from PasswordResetToken token join fetch token.user where token.tokenHash = :hash")
	Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("hash") String hash);

	@Modifying
	@Query("delete from PasswordResetToken token where token.user.id = :userId")
	int deleteAllForUser(@Param("userId") UUID userId);

	@Modifying
	@Query("delete from PasswordResetToken token where token.user.id = :userId and token.id <> :keepId")
	int deleteOtherTokensForUser(@Param("userId") UUID userId, @Param("keepId") UUID keepId);

	@Modifying
	@Query("delete from PasswordResetToken token where token.expiresAt < :cutoff or token.consumedAt < :cutoff")
	int purgeBefore(@Param("cutoff") Instant cutoff);
}
