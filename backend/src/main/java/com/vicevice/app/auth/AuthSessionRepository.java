package com.vicevice.app.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {
    Optional<AuthSession> findByTokenHashAndExpiresAtEpochMsGreaterThan(String tokenHash, Long now);
}
