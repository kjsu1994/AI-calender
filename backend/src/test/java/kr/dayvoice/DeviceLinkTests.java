package kr.dayvoice;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceLinkTests {
    private final AtomicLong now = new AtomicLong(Instant.parse("2026-09-25T08:00:00Z").toEpochMilli());
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    private final Store store = mock(Store.class);
    private final AppConfig.Settings settings = new AppConfig.Settings("http://localhost:8085", false,
        "test@example.com", "", "", "", "", "", "");
    private final DeviceLinkController controller = new DeviceLinkController(settings, store, clock);

    private Store.User user(String email, boolean demo) {
        return new Store.User("test-user", email, "Test", "encrypted-token", "calendar", "Calendar", "", "SYNCED", null, demo);
    }

    private MockHttpServletRequest request(boolean signedIn) {
        var request = new MockHttpServletRequest();
        request.setServerName("192.168.1.10");
        request.setServerPort(8085);
        if (signedIn) request.getSession().setAttribute("uid", "test-user");
        return request;
    }

    private String issue() {
        when(store.user("test-user")).thenReturn(user("test@example.com", false));
        return controller.issue(request(true)).get("code").toString();
    }

    private Map<String, Object> redeem(String code, MockHttpServletRequest request) {
        return controller.link(new DeviceLinkController.LinkRequest(code), request);
    }

    private void rejects(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ApiException.class, action).status);
    }

    @Test void successRotatesSessionClearsCsrfAndAcceptsFormattedInput() {
        when(store.user("test-user")).thenReturn(user("test@example.com", false));
        var response = controller.issue(request(true));
        String code = response.get("code").toString();
        assertTrue(code.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}(-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}){2}"));
        assertEquals("2026-09-25T08:05:00Z", response.get("expiresAt"));
        var target = request(false);
        String previousId = target.getSession().getId();
        String csrfKey = "org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN";
        target.getSession().setAttribute(csrfKey, "old-csrf");

        assertEquals(Map.of("ok", true), redeem(" " + code.toLowerCase(Locale.ROOT).replace('-', ' ') + " ", target));

        assertEquals("test-user", target.getSession().getAttribute("uid"));
        assertNotEquals(previousId, target.getSession().getId());
        assertNull(target.getSession().getAttribute(csrfKey));
        rejects(400, () -> redeem(code, request(false)));
    }

    @Test void issueRequiresAuthenticatedAllowedRealUser() {
        rejects(401, () -> controller.issue(request(false)));
        when(store.user("test-user")).thenReturn(user("test@example.com", true));
        rejects(403, () -> controller.issue(request(true)));
        when(store.user("test-user")).thenReturn(user("different@example.com", false));
        rejects(403, () -> controller.issue(request(true)));
    }

    @Test void wrongOrExpiredCodeCannotSignIn() {
        String code = issue();
        var target = request(false);
        rejects(400, () -> redeem("invalid", target));
        String unknownCode = (code.charAt(0) == 'A' ? "B" : "A") + code.substring(1);
        rejects(400, () -> redeem(unknownCode, target));
        assertNull(target.getSession().getAttribute("uid"));
        now.addAndGet(300_000);
        rejects(400, () -> redeem(code, target));
        assertNull(target.getSession().getAttribute("uid"));
    }

    @Test void allowlistAndRealAccountAreCheckedAgainOnRedemption() {
        String deniedCode = issue();
        when(store.user("test-user")).thenReturn(user("removed@example.com", false));
        rejects(400, () -> redeem(deniedCode, request(false)));
        now.addAndGet(5_000);
        String demoCode = issue();
        when(store.user("test-user")).thenReturn(user("test@example.com", true));
        rejects(400, () -> redeem(demoCode, request(false)));
    }

    @Test void existingSessionCannotSwitchAccountsOrConsumeCode() {
        String code = issue();
        var signedIn = request(true);
        signedIn.getSession().setAttribute("uid", "other-user");
        rejects(409, () -> redeem(code, signedIn));
        assertEquals("other-user", signedIn.getSession().getAttribute("uid"));
        assertEquals(Map.of("ok", true), redeem(code, request(false)));
    }

    @Test void secondIssuanceInvalidatesPreviousCodeAndIssuanceIsThrottled() {
        String first = issue();
        rejects(429, () -> controller.issue(request(true)));
        now.addAndGet(5_000);
        String second = issue();
        assertNotEquals(first, second);
        rejects(400, () -> redeem(first, request(false)));
        assertEquals(Map.of("ok", true), redeem(second, request(false)));
    }

    @Test void redemptionPerSessionLimitResetsAfterOneMinute() {
        String code = issue();
        var target = request(false);
        for (int i = 0; i < 5; i++) rejects(400, () -> redeem("invalid", target));
        rejects(429, () -> redeem(code, target));
        now.addAndGet(60_000);
        assertEquals(Map.of("ok", true), redeem(code, target));
    }

    @Test void globalLimitAppliesAcrossSessionsAndResetsAfterOneMinute() {
        String code = issue();
        for (int i = 0; i < 60; i++) rejects(400, () -> redeem("invalid", request(false)));
        rejects(429, () -> redeem(code, request(false)));
        now.addAndGet(60_000);
        assertEquals(Map.of("ok", true), redeem(code, request(false)));
    }

    @Test void concurrentRedemptionsHaveExactlyOneWinner() throws Exception {
        String code = issue();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { redeem(code, request(false)); return true; }
                catch (ApiException e) { assertEquals(400, e.status); return false; }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
    }
}
