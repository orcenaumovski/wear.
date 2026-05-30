package com.vicevice.app.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "auth_session")
public class AuthSession {
    @Id
    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "created_at_epoch_ms", nullable = false)
    private Long createdAtEpochMs;

    @Column(name = "expires_at_epoch_ms", nullable = false)
    private Long expiresAtEpochMs;

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public Long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    public void setCreatedAtEpochMs(Long createdAtEpochMs) {
        this.createdAtEpochMs = createdAtEpochMs;
    }

    public Long getExpiresAtEpochMs() {
        return expiresAtEpochMs;
    }

    public void setExpiresAtEpochMs(Long expiresAtEpochMs) {
        this.expiresAtEpochMs = expiresAtEpochMs;
    }
}
