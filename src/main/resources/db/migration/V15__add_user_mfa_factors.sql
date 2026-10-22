CREATE TABLE user_mfa_factors (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret_ciphertext TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    enrollment_expires_at TIMESTAMPTZ,
    last_accepted_step BIGINT NOT NULL DEFAULT -1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_mfa_enrollment_state CHECK (
        (enabled AND enrollment_expires_at IS NULL) OR
        (NOT enabled AND enrollment_expires_at IS NOT NULL)
    )
);

CREATE TABLE user_mfa_recovery_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_mfa_factors(user_id) ON DELETE CASCADE,
    code_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at TIMESTAMPTZ,
    CONSTRAINT user_mfa_recovery_code_hash_unique UNIQUE(user_id, code_hash)
);

CREATE INDEX user_mfa_recovery_codes_unused_idx ON user_mfa_recovery_codes(user_id, used_at);
