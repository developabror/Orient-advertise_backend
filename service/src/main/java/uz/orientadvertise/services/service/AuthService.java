package uz.orientadvertise.services.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.auth.AuthToken;
import uz.orientadvertise.services.domain.auth.LoginRequest;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.infra.auth.JwtTokenProvider;
import uz.orientadvertise.services.infra.auth.RefreshTokenRepository;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(JwtTokenProvider jwtTokenProvider,
                       RefreshTokenRepository refreshTokenRepository,
                       AppUserRepository userRepository,
                       PasswordEncoder passwordEncoder) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthToken login(LoginRequest request) {
        // Every failure path returns the SAME generic 401 — no-such-user, wrong password,
        // deactivated, and no-role are indistinguishable to the caller (no account
        // enumeration). The password is verified with a constant-time BCrypt comparison.
        var user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new AuthenticationException("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new AuthenticationException("Invalid credentials");
        }

        if (!user.isActive() || user.getRole() == null) {
            throw new AuthenticationException("Invalid credentials");
        }

        var familyId = UUID.randomUUID().toString();
        var accessToken = jwtTokenProvider.createAccessToken(user.getUsername(), user.getRole());
        var refreshTokenId = refreshTokenRepository.store(user.getUsername(), familyId);

        log.info("User [{}] logged in with role [{}], family [{}]", user.getUsername(), user.getRole(), familyId);
        return new AuthToken(accessToken, refreshTokenId);
    }

    public AuthToken refresh(String refreshTokenId) {
        var tokenData = refreshTokenRepository.find(refreshTokenId);

        if (tokenData.isEmpty()) {
            // Never log the raw token id — log a short SHA-256 prefix so the event is
            // correlatable without putting a (potentially still-valid elsewhere) secret in logs.
            log.warn("Refresh token reuse detected or token expired [tokenHash={}]", hashPrefix(refreshTokenId));
            throw new AuthenticationException("Invalid refresh token — possible reuse attack");
        }

        var data = tokenData.get();

        var user = userRepository.findByUsername(data.userId())
                .orElseThrow(() -> new AuthenticationException("User no longer exists"));

        if (!user.isActive()) {
            refreshTokenRepository.invalidateFamily(data.familyId());
            throw new AuthenticationException("Account is deactivated");
        }

        if (user.getRole() == null) {
            refreshTokenRepository.invalidateFamily(data.familyId());
            throw new AuthenticationException("User has no assigned role — access revoked");
        }

        refreshTokenRepository.delete(refreshTokenId);
        refreshTokenRepository.removeFromFamily(data.familyId(), refreshTokenId);

        var newAccessToken = jwtTokenProvider.createAccessToken(data.userId(), user.getRole());
        var newRefreshTokenId = refreshTokenRepository.store(data.userId(), data.familyId());

        log.debug("Rotated refresh token for user [{}], role [{}], family [{}]",
                data.userId(), user.getRole(), data.familyId());
        return new AuthToken(newAccessToken, newRefreshTokenId);
    }

    /** First 12 hex chars of SHA-256(token) — enough to correlate logs, useless to replay. */
    private static String hashPrefix(String token) {
        if (token == null) {
            return "null";
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (Exception e) {
            return "unknown";
        }
    }

    public void logout(String refreshTokenId) {
        var tokenData = refreshTokenRepository.find(refreshTokenId);

        if (tokenData.isPresent()) {
            var data = tokenData.get();
            refreshTokenRepository.invalidateFamily(data.familyId());
            log.info("User [{}] logged out, family [{}] invalidated", data.userId(), data.familyId());
        }
    }
}
