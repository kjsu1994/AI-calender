package kr.dayvoice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class LocalAuth implements ApplicationRunner {
    static final String COOKIE = "DAYVOICE_REMEMBER";
    static final String REVISION = "dayvoice.passwordRevision";
    static final String REMEMBER_HASH = "dayvoice.rememberHash";
    static final String CSRF_TOKEN = "org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN";
    private static final String ATTEMPTS = "dayvoice.passwordAttempts";
    private static final Duration REMEMBER_DURATION = Duration.ofDays(30);
    private static final long RATE_WINDOW = 60_000;
    private final AppConfig.Settings app;
    private final Store store;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Credentials credentials;
    private final boolean secureCookie;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final ArrayDeque<Long> globalAttempts = new ArrayDeque<>();
    private final Map<String, ArrayDeque<Long>> usernameAttempts = new HashMap<>();
    private volatile String ownerId;

    private record Credentials(String username, String email, String passwordHash, String revision) {}
    private record Remembered(String userId, String revision, long expiresAt) {}

    @Autowired
    LocalAuth(AppConfig.Settings app, Store store, TransactionTemplate tx, Environment environment) {
        this(app, store, tx, environment, Clock.systemUTC());
    }

    LocalAuth(AppConfig.Settings app, Store store, TransactionTemplate tx, Environment environment, Clock clock) {
        this.app = app;
        this.store = store;
        this.tx = tx;
        this.clock = clock;
        this.credentials = readCredentials(environment);
        this.secureCookie = environment.getProperty("COOKIE_SECURE", Boolean.class,
            environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false));
    }

    @Override
    public void run(ApplicationArguments ignored) { initialize(); }

    synchronized void initialize() {
        if (!configured()) {
            store.db.update("DELETE FROM remembered_login");
            ownerId = null;
            return;
        }
        if (!Arrays.stream(app.allowedEmails().split(",")).map(String::trim)
            .anyMatch(email -> email.equalsIgnoreCase(credentials.email())))
            throw new IllegalStateException("LOCAL_LOGIN_EMAIL must be present in ALLOWED_EMAILS");
        ownerId = tx.execute(status -> {
            var users = store.db.queryForList("SELECT id FROM app_user WHERE LOWER(email)=? AND demo=FALSE",
                String.class, credentials.email());
            if (users.size() > 1) throw new IllegalStateException("Local login email matches multiple accounts; resolve the duplicate accounts first");
            String userId;
            if (users.isEmpty()) {
                userId = "local-" + UUID.randomUUID();
                store.db.update("INSERT INTO app_user(id,email,display_name,demo) VALUES(?,?,?,FALSE)",
                    userId, credentials.email(), credentials.username());
            } else userId = users.getFirst();
            int count = store.db.queryForObject("SELECT COUNT(*) FROM local_login WHERE id='owner'", Integer.class);
            if (count == 0) store.db.update("INSERT INTO local_login(id,user_id,username,credential_revision) VALUES('owner',?,?,?)",
                userId, credentials.username(), credentials.revision());
            else store.db.update("UPDATE local_login SET user_id=?,username=?,credential_revision=? WHERE id='owner'",
                userId, credentials.username(), credentials.revision());
            store.db.update("DELETE FROM remembered_login WHERE credential_revision<>? OR user_id<>? OR expires_at<=?",
                credentials.revision(), userId, clock.millis());
            return userId;
        });
    }

    boolean configured() { return credentials != null && !app.demoMode(); }

    boolean remembered(HttpSession session) {
        return session != null && session.getAttribute(REMEMBER_HASH) != null;
    }

    void login(String username, String password, Boolean rememberMe, HttpServletRequest request, HttpServletResponse response) {
        if (!configured() || ownerId == null) throw new ApiException(503, "아이디 로그인이 아직 설정되지 않았습니다.");
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        countAttempt(request.getSession(), normalized);
        if (normalized.isBlank() || normalized.length() > 64 || password == null || password.isEmpty()
            || password.length() > 72 || password.getBytes(StandardCharsets.UTF_8).length > 72) throw invalidLogin();
        // Always check the configured BCrypt hash for a well-formed attempt, including an unknown username.
        boolean passwordMatches = encoder.matches(password, credentials.passwordHash());
        boolean usernameMatches = MessageDigest.isEqual(normalized.getBytes(StandardCharsets.UTF_8),
            credentials.username().getBytes(StandardCharsets.UTF_8));
        if (!passwordMatches || !usernameMatches || !ownerAllowed(ownerId)) throw invalidLogin();
        Object previous = request.getSession().getAttribute("uid");
        if (previous != null && !ownerId.equals(previous.toString()))
            throw new ApiException(409, "다른 계정으로 로그인되어 있습니다. 먼저 로그아웃해 주세요.");
        revokePresentedToken(request);
        boolean remember = rememberMe == null || rememberMe;
        String token = null, digest = null;
        if (remember) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            digest = hash(token);
            store.db.update("DELETE FROM remembered_login WHERE expires_at<=?", clock.millis());
            store.db.update("INSERT INTO remembered_login(token_hash,user_id,credential_revision,created_at,expires_at) VALUES(?,?,?,?,?)",
                digest, ownerId, credentials.revision(), clock.millis(), clock.millis() + REMEMBER_DURATION.toMillis());
        }
        establishSession(request, ownerId, digest);
        writeCookie(response, token, remember ? REMEMBER_DURATION : Duration.ZERO);
    }

    void restore(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(REVISION) != null) {
            Object uid = session.getAttribute("uid");
            String digest = Objects.toString(session.getAttribute(REMEMBER_HASH), "");
            boolean valid = configured() && credentials.revision().equals(session.getAttribute(REVISION))
                && ownerId != null && ownerId.equals(uid) && ownerAllowed(ownerId);
            if (valid && !digest.isBlank()) valid = validRemembered(findRemembered(digest));
            if (!valid) {
                revokePresentedToken(request);
                session.invalidate();
                writeCookie(response, null, Duration.ZERO);
                return;
            }
        }
        if (session != null && session.getAttribute("uid") != null) return;
        String token = cookieToken(request);
        if (token == null) return;
        String digest = token.matches("[A-Za-z0-9_-]{43}") ? hash(token) : null;
        Remembered remembered = digest == null ? null : findRemembered(digest);
        if (!validRemembered(remembered)) {
            if (digest != null) store.db.update("DELETE FROM remembered_login WHERE token_hash=?", digest);
            writeCookie(response, null, Duration.ZERO);
            return;
        }
        establishSession(request, remembered.userId(), digest);
    }

    void logout(HttpServletRequest request, HttpServletResponse response) {
        revokePresentedToken(request);
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        writeCookie(response, null, Duration.ZERO);
    }

    private void establishSession(HttpServletRequest request, String userId, String digest) {
        HttpSession session = request.getSession();
        request.changeSessionId();
        session.setAttribute("uid", userId);
        session.setAttribute(REVISION, credentials.revision());
        if (digest == null) session.removeAttribute(REMEMBER_HASH); else session.setAttribute(REMEMBER_HASH, digest);
        session.removeAttribute(CSRF_TOKEN);
        session.removeAttribute(ATTEMPTS);
        session.removeAttribute("oauthState");
        session.removeAttribute("oauthTime");
        session.removeAttribute("oauthVerifier");
    }

    private boolean ownerAllowed(String userId) {
        if (!configured() || ownerId == null || !ownerId.equals(userId)) return false;
        try {
            var user = store.user(userId);
            return !user.demo() && credentials.email().equalsIgnoreCase(user.email());
        } catch (ApiException ignored) { return false; }
    }

    private Remembered findRemembered(String digest) {
        return store.db.query("SELECT user_id,credential_revision,expires_at FROM remembered_login WHERE token_hash=?",
            (rs, row) -> new Remembered(rs.getString("user_id"), rs.getString("credential_revision"), rs.getLong("expires_at")), digest)
            .stream().findFirst().orElse(null);
    }

    private boolean validRemembered(Remembered token) {
        return token != null && configured() && token.expiresAt() > clock.millis()
            && credentials.revision().equals(token.revision()) && ownerAllowed(token.userId());
    }

    private void revokePresentedToken(HttpServletRequest request) {
        String token = cookieToken(request);
        if (token != null && token.matches("[A-Za-z0-9_-]{43}"))
            store.db.update("DELETE FROM remembered_login WHERE token_hash=?", hash(token));
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(REMEMBER_HASH) instanceof String digest)
            store.db.update("DELETE FROM remembered_login WHERE token_hash=?", digest);
    }

    private static String cookieToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(cookie -> COOKIE.equals(cookie.getName()))
            .map(Cookie::getValue).findFirst().orElse(null);
    }

    private void writeCookie(HttpServletResponse response, String token, Duration duration) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, token == null ? "" : token)
            .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/").maxAge(duration).build().toString());
    }

    @SuppressWarnings("unchecked")
    private synchronized void countAttempt(HttpSession session, String username) {
        long now = clock.millis();
        String rateKey = username.length() > 64 ? username.substring(0,64) : username;
        prune(globalAttempts, now);
        usernameAttempts.values().forEach(attempts -> prune(attempts, now));
        usernameAttempts.values().removeIf(ArrayDeque::isEmpty);
        var attempts = (ArrayDeque<Long>) session.getAttribute(ATTEMPTS);
        if (attempts == null) {
            attempts = new ArrayDeque<>();
            session.setAttribute(ATTEMPTS, attempts);
        }
        prune(attempts, now);
        var byUsername = usernameAttempts.get(rateKey);
        if (globalAttempts.size() >= 60 || attempts.size() >= 5 || byUsername != null && byUsername.size() >= 10)
            throw new ApiException(429, "로그인 시도가 너무 많습니다. 1분 후 다시 시도해 주세요.");
        if (byUsername == null) {
            byUsername = new ArrayDeque<>();
            usernameAttempts.put(rateKey, byUsername);
        }
        globalAttempts.addLast(now);
        attempts.addLast(now);
        byUsername.addLast(now);
    }

    private static void prune(ArrayDeque<Long> attempts, long now) {
        while (!attempts.isEmpty() && attempts.peekFirst() <= now - RATE_WINDOW) attempts.removeFirst();
    }

    private static Credentials readCredentials(Environment environment) {
        String username = environment.getProperty("LOCAL_LOGIN_USERNAME", "").trim().toLowerCase(Locale.ROOT);
        String email = environment.getProperty("LOCAL_LOGIN_EMAIL", "").trim().toLowerCase(Locale.ROOT);
        String encoded = environment.getProperty("LOCAL_LOGIN_PASSWORD_HASH_BASE64", "").trim();
        if (username.isBlank() && email.isBlank() && encoded.isBlank()) return null;
        if (!username.matches("[a-z0-9._-]{3,64}") || email.length() > 320 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || encoded.length() > 256)
            throw new IllegalStateException("Set a valid LOCAL_LOGIN_USERNAME, LOCAL_LOGIN_EMAIL and LOCAL_LOGIN_PASSWORD_HASH_BASE64");
        String passwordHash;
        try { passwordHash = new String(Base64.getDecoder().decode(encoded), StandardCharsets.US_ASCII); }
        catch (IllegalArgumentException ignored) { throw new IllegalStateException("LOCAL_LOGIN_PASSWORD_HASH_BASE64 must encode a BCrypt hash with cost 12"); }
        if (!passwordHash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}"))
            throw new IllegalStateException("LOCAL_LOGIN_PASSWORD_HASH_BASE64 must encode a BCrypt hash with cost 12");
        return new Credentials(username, email, passwordHash, hash(username + "\n" + email + "\n" + passwordHash));
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private static ApiException invalidLogin() { return new ApiException(401, "아이디 또는 비밀번호가 올바르지 않습니다."); }
}
