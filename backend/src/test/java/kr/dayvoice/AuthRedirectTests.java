package kr.dayvoice;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthRedirectTests {
    private static final String PUBLIC_ORIGIN = "http://localhost:8085";
    private static final String CLOUDFLARE_ORIGIN = "https://ctfmon.shop";
    private final HttpGateway http = mock(HttpGateway.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final GoogleAccountLinker accountLinker = mock(GoogleAccountLinker.class);

    private AuthController controller(String baseUrl) {
        var settings = new AppConfig.Settings(baseUrl, false, "test@example.com",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "test-client", "test-secret",
            "", "test-model", "ffmpeg");
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(accountLinker.link(isNull(),anyString(),anyString(),anyString(),anyString())).thenReturn("test-user");
        return new AuthController(settings, new Store(db), http,
            new TransactionTemplate(transactions), mock(SyncService.class),mock(LocalAuth.class),accountLinker);
    }

    private MockHttpServletRequest internalCallbackRequest() {
        var request = new MockHttpServletRequest("GET", "/api/auth/google/callback");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        request.addHeader("Host", "127.0.0.1:8080");
        request.getSession().setAttribute("oauthState", "test-state");
        request.getSession().setAttribute("oauthVerifier", "test-verifier");
        request.getSession().setAttribute("oauthTime", System.currentTimeMillis());
        return request;
    }

    private void tokenReply(int status) {
        when(http.form(eq("https://oauth2.googleapis.com/token"), anyMap()))
            .thenReturn(new HttpGateway.Reply(status,
                Json.read("{\"access_token\":\"test-access\",\"refresh_token\":\"test-refresh\"}")));
    }

    private void userReply(String email) {
        when(http.send(eq("GET"), eq("https://openidconnect.googleapis.com/v1/userinfo"),
            isNull(), eq(Map.of("Authorization", "Bearer test-access"))))
            .thenReturn(new HttpGateway.Reply(200, Json.object()
                .put("email", email).put("email_verified", true).put("sub", "test-user").put("name", "Test User")));
    }

    @ParameterizedTest
    @ValueSource(strings = {PUBLIC_ORIGIN, PUBLIC_ORIGIN + "/", CLOUDFLARE_ORIGIN, CLOUDFLARE_ORIGIN + "/"})
    void successfulCallbackReturnsToPublicFrontendAndRetainsLogin(String baseUrl) throws Exception {
        tokenReply(200);
        userReply("test@example.com");
        var request = internalCallbackRequest();
        String previousSessionId = request.getSession().getId();
        var response = new MockHttpServletResponse();

        controller(baseUrl).callback("test-code", "test-state", request, response);

        assertEquals(302, response.getStatus());
        assertEquals(publicRoot(baseUrl), response.getRedirectedUrl());
        assertEquals("test-user", request.getSession().getAttribute("uid"));
        assertNotEquals(previousSessionId, request.getSession().getId());
        assertNull(request.getSession().getAttribute("oauthState"));
        verify(accountLinker).link(null,"test-user","test@example.com","Test User","test-refresh");
        verify(http).form(eq("https://oauth2.googleapis.com/token"), argThat(fields ->
            (publicRoot(baseUrl) + "api/auth/google/callback").equals(fields.get("redirect_uri"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {PUBLIC_ORIGIN, PUBLIC_ORIGIN + "/", CLOUDFLARE_ORIGIN, CLOUDFLARE_ORIGIN + "/"})
    void invalidCallbackReturnsToPublicFrontendWithoutContactingGoogle(String baseUrl) throws Exception {
        var request = internalCallbackRequest();
        var response = new MockHttpServletResponse();

        controller(baseUrl).callback("test-code", "wrong-state", request, response);

        assertEquals(publicRoot(baseUrl) + "?authError=invalid", response.getRedirectedUrl());
        assertNull(request.getSession().getAttribute("uid"));
        verifyNoInteractions(http, db);
    }

    @ParameterizedTest
    @ValueSource(strings = {PUBLIC_ORIGIN, PUBLIC_ORIGIN + "/", CLOUDFLARE_ORIGIN, CLOUDFLARE_ORIGIN + "/"})
    void rejectedTokenReturnsToPublicFrontendWithoutLoggingIn(String baseUrl) throws Exception {
        tokenReply(400);
        var request = internalCallbackRequest();
        var response = new MockHttpServletResponse();

        controller(baseUrl).callback("test-code", "test-state", request, response);

        assertEquals(publicRoot(baseUrl) + "?authError=token", response.getRedirectedUrl());
        assertNull(request.getSession().getAttribute("uid"));
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @ValueSource(strings = {PUBLIC_ORIGIN, PUBLIC_ORIGIN + "/", CLOUDFLARE_ORIGIN, CLOUDFLARE_ORIGIN + "/"})
    void unapprovedAccountReturnsToPublicFrontendWithoutLoggingIn(String baseUrl) throws Exception {
        tokenReply(200);
        userReply("unapproved@example.com");
        var request = internalCallbackRequest();
        var response = new MockHttpServletResponse();

        controller(baseUrl).callback("test-code", "test-state", request, response);

        assertEquals(publicRoot(baseUrl) + "?authError=denied", response.getRedirectedUrl());
        assertNull(request.getSession().getAttribute("uid"));
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @ValueSource(strings = {PUBLIC_ORIGIN, PUBLIC_ORIGIN + "/", CLOUDFLARE_ORIGIN, CLOUDFLARE_ORIGIN + "/"})
    void loginStartUsesConfiguredPublicCallbackFromInternalHttpOrigin(String baseUrl) throws Exception {
        var request = internalCallbackRequest();
        request.setRequestURI("/api/auth/google");
        var response = new MockHttpServletResponse();

        controller(baseUrl).login(request, response);

        URI authorization = URI.create(response.getRedirectedUrl());
        assertEquals(302, response.getStatus());
        assertEquals("https", authorization.getScheme());
        assertEquals("accounts.google.com", authorization.getHost());
        assertEquals("/o/oauth2/v2/auth", authorization.getPath());
        Map<String,String> query = new HashMap<>();
        for (String field : authorization.getRawQuery().split("&")) {
            String[] pair = field.split("=", 2);
            query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        assertEquals(publicRoot(baseUrl) + "api/auth/google/callback", query.get("redirect_uri"));
        assertEquals("test-client", query.get("client_id"));
        assertEquals("code", query.get("response_type"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals(request.getSession().getAttribute("oauthState"), query.get("state"));
        assertNotEquals("test-state", query.get("state"));
        assertFalse(query.containsKey("client_secret"));
        verifyNoInteractions(http, db);
    }

    private static String publicRoot(String baseUrl) {
        return URI.create(baseUrl).resolve("/").toString();
    }
}
