package kr.dayvoice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
class DeviceLinkController {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long CODE_TTL_MS = 300_000, RATE_WINDOW_MS = 60_000, ISSUE_PAUSE_MS = 5_000;
    private static final int MAX_CODES = 1_000;
    private static final String ATTEMPTS = DeviceLinkController.class.getName() + ".attempts";
    private static final String CSRF_TOKEN = "org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN";
    private final AppConfig.Settings cfg;
    private final Store store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    // Only code digests are retained. Links intentionally disappear when this single backend restarts.
    private final Map<String, Link> links = new HashMap<>();
    private final Map<String, Long> lastIssued = new HashMap<>();
    private final ArrayDeque<Long> globalAttempts = new ArrayDeque<>();

    private record Link(String userId, Instant expiresAt) {}
    record LinkRequest(String code) {}

    @Autowired
    public DeviceLinkController(AppConfig.Settings cfg, Store store) {
        this(cfg, store, Clock.systemUTC());
    }

    DeviceLinkController(AppConfig.Settings cfg, Store store, Clock clock) {
        this.cfg = cfg;
        this.store = store;
        this.clock = clock;
    }

    @PostMapping("/api/auth/device-code")
    synchronized Map<String, Object> issue(HttpServletRequest request) {
        String userId = AuthController.uid(request.getSession());
        if (!allowed(store.user(userId))) throw new ApiException(403, "허용된 Google 계정으로 로그인한 뒤 기기를 연결해 주세요.");
        long now = clock.millis();
        prune(now);
        if (lastIssued.containsKey(userId)) throw rateLimited();
        if (lastIssued.size() >= MAX_CODES) throw rateLimited();
        links.values().removeIf(link -> link.userId().equals(userId));
        if (links.size() >= MAX_CODES) throw rateLimited();
        String code, digest;
        do {
            var chars = new StringBuilder(12);
            for (int i = 0; i < 12; i++) chars.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            code = chars.toString();
            digest = hash(code);
        } while (links.containsKey(digest));
        Instant expiresAt = Instant.ofEpochMilli(now + CODE_TTL_MS);
        links.put(digest, new Link(userId, expiresAt));
        lastIssued.put(userId, now);
        return Map.of("code", code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8),
            "expiresAt", expiresAt.toString());
    }

    @PostMapping("/api/auth/device-link")
    synchronized Map<String, Object> link(@RequestBody LinkRequest body, HttpServletRequest request) {
        HttpSession session = request.getSession();
        if (session.getAttribute("uid") != null) throw new ApiException(409, "이미 로그인되어 있습니다. 다른 계정을 연결하려면 먼저 로그아웃해 주세요.");
        long now = clock.millis();
        prune(now);
        countAttempt(session, now);
        String code = normalize(body == null ? null : body.code());
        Link link = links.remove(hash(code));
        if (link == null) throw invalidCode();
        Store.User user;
        try { user = store.user(link.userId()); }
        catch (ApiException ignored) { throw invalidCode(); }
        if (!allowed(user)) throw invalidCode();
        request.changeSessionId();
        session.setAttribute("uid", user.id());
        session.removeAttribute(CSRF_TOKEN);
        session.removeAttribute(ATTEMPTS);
        return Map.of("ok", true);
    }

    private boolean allowed(Store.User user) {
        return !cfg.demoMode() && !user.demo() && Arrays.stream(cfg.allowedEmails().split(","))
            .map(String::trim).anyMatch(email -> !email.isBlank() && email.equalsIgnoreCase(user.email()));
    }

    private void prune(long now) {
        links.values().removeIf(link -> !link.expiresAt().isAfter(Instant.ofEpochMilli(now)));
        lastIssued.values().removeIf(issuedAt -> issuedAt <= now - ISSUE_PAUSE_MS);
        pruneAttempts(globalAttempts, now);
    }

    @SuppressWarnings("unchecked")
    private void countAttempt(HttpSession session, long now) {
        if (globalAttempts.size() >= 60) throw rateLimited();
        var attempts = (ArrayDeque<Long>) session.getAttribute(ATTEMPTS);
        if (attempts == null) {
            attempts = new ArrayDeque<>();
            session.setAttribute(ATTEMPTS, attempts);
        }
        pruneAttempts(attempts, now);
        if (attempts.size() >= 5) throw rateLimited();
        attempts.addLast(now);
        globalAttempts.addLast(now);
    }

    private static void pruneAttempts(ArrayDeque<Long> attempts, long now) {
        while (!attempts.isEmpty() && attempts.peekFirst() <= now - RATE_WINDOW_MS) attempts.removeFirst();
    }

    private static String normalize(String input) {
        if (input == null || input.length() > 64) throw invalidCode();
        String code = input.replace("-", "").replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (code.length() != 12 || code.chars().anyMatch(c -> ALPHABET.indexOf(c) < 0)) throw invalidCode();
        return code;
    }

    private static String hash(String code) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private static ApiException invalidCode() { return new ApiException(400, "연결 코드가 올바르지 않거나 만료되었습니다. PC에서 새 코드를 발급해 주세요."); }
    private static ApiException rateLimited() { return new ApiException(429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."); }
}
