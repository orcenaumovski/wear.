package com.vicevice.app.auth;

import com.vicevice.app.item.Item;
import com.vicevice.app.item.ItemRepository;
import com.vicevice.app.outfit.SavedOutfit;
import com.vicevice.app.outfit.SavedOutfitRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
public class AuthService {
    private static final int PASSWORD_ITERATIONS = 120_000;
    private static final int PASSWORD_KEY_LENGTH = 256;
    private static final Duration SESSION_DURATION = Duration.ofDays(30);
    private final UserAccountRepository userAccountRepository;
    private final AuthSessionRepository authSessionRepository;
    private final ItemRepository itemRepository;
    private final SavedOutfitRepository savedOutfitRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            UserAccountRepository userAccountRepository,
            AuthSessionRepository authSessionRepository,
            ItemRepository itemRepository,
            SavedOutfitRepository savedOutfitRepository
    ) {
        this.userAccountRepository = userAccountRepository;
        this.authSessionRepository = authSessionRepository;
        this.itemRepository = itemRepository;
        this.savedOutfitRepository = savedOutfitRepository;
    }

    public AuthResponse register(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        if (userAccountRepository.existsByUsername(normalizedUsername)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists.");
        }

        UserAccount user = new UserAccount();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(hashPassword(password));
        user.setCreatedAtEpochMs(System.currentTimeMillis());
        userAccountRepository.save(user);
        claimExistingLocalData(user.getId());

        return createSession(user);
    }

    public AuthResponse login(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        UserAccount user = userAccountRepository.findByUsername(normalizedUsername)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password."));

        if (!verifyPassword(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
        }

        return createSession(user);
    }

    public AuthenticatedUser requireUser(String authorizationHeader) {
        return requireUser(authorizationHeader, null);
    }

    public AuthenticatedUser requireUser(String authorizationHeader, String tokenParam) {
        String token = tokenFrom(authorizationHeader, tokenParam);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required.");
        }

        AuthSession session = authSessionRepository
                .findByTokenHashAndExpiresAtEpochMsGreaterThan(hashToken(token), System.currentTimeMillis())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required."));

        UserAccount user = userAccountRepository.findById(session.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required."));
        return new AuthenticatedUser(user.getId(), user.getUsername());
    }

    public AuthenticatedUser me(String authorizationHeader) {
        return requireUser(authorizationHeader);
    }

    private AuthResponse createSession(UserAccount user) {
        String token = randomToken(32);
        long now = System.currentTimeMillis();

        AuthSession session = new AuthSession();
        session.setTokenHash(hashToken(token));
        session.setUserId(user.getId());
        session.setCreatedAtEpochMs(now);
        session.setExpiresAtEpochMs(now + SESSION_DURATION.toMillis());
        authSessionRepository.save(session);

        return new AuthResponse(token, new AuthenticatedUser(user.getId(), user.getUsername()));
    }

    private void claimExistingLocalData(Integer userId) {
        List<Item> orphanedItems = itemRepository.findByUserIdIsNull();
        for (Item item : orphanedItems) {
            item.setUserId(userId);
        }
        itemRepository.saveAll(orphanedItems);

        List<SavedOutfit> orphanedOutfits = savedOutfitRepository.findByUserIdIsNull();
        for (SavedOutfit outfit : orphanedOutfits) {
            outfit.setUserId(userId);
        }
        savedOutfitRepository.saveAll(orphanedOutfits);
    }

    private String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 1 || normalized.length() > 40) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must be 1 to 40 characters.");
        }
        if (!normalized.matches("[\\p{L}\\p{N}._ -]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username can only use letters, numbers, spaces, dots, dashes, and underscores.");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 1 || password.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be 1 to 128 characters.");
        }
    }

    private String hashPassword(String password) {
        byte[] salt = randomBytes(16);
        byte[] hash = pbkdf2(password.toCharArray(), salt);
        return PASSWORD_ITERATIONS + ":" + b64(salt) + ":" + b64(hash);
    }

    private boolean verifyPassword(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }

        String[] parts = stored.split(":");
        if (parts.length != 3) {
            return false;
        }

        try {
            byte[] salt = Base64.getUrlDecoder().decode(parts[1]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[2]);
            byte[] actual = pbkdf2(password.toCharArray(), salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not secure account password.", e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private String hashToken(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return b64(hash);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not secure session token.", e);
        }
    }

    private String tokenFrom(String authorizationHeader, String tokenParam) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring("Bearer ".length()).trim();
            return token.isEmpty() ? null : token;
        }
        if (tokenParam != null && !tokenParam.isBlank()) {
            return tokenParam.trim();
        }
        return null;
    }

    private String randomToken(int bytes) {
        return b64(randomBytes(bytes));
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record AuthenticatedUser(Integer id, String username) {}

    public record AuthResponse(String token, AuthenticatedUser user) {}
}
