package kr.dayvoice;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:password-auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "app.demo-mode=false", "app.allowed-emails=owner-local@example.com,other@example.com",
    "app.base-url=https://ctfmon.shop", "COOKIE_SECURE=true"
})
@AutoConfigureMockMvc
@Transactional
class LocalAuthTests {
    private static final String USERNAME = "test-owner";
    private static final String EMAIL = "owner-local@example.com";
    // Random test-only credentials, unrelated to the configured owner's password.
    private static final String PASSWORD = "test-" + UUID.randomUUID();
    private static final String HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    private static final String ENCODED_HASH = Base64.getEncoder().encodeToString(HASH.getBytes(StandardCharsets.US_ASCII));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("LOCAL_LOGIN_USERNAME", () -> USERNAME);
        properties.add("LOCAL_LOGIN_EMAIL", () -> EMAIL);
        properties.add("LOCAL_LOGIN_PASSWORD_HASH_BASE64", () -> ENCODED_HASH);
    }

    @Autowired MockMvc mvc;
    @Autowired Store store;
    @Autowired TransactionTemplate tx;
    @Autowired AppConfig.Settings settings;
    @Autowired Environment environment;
    @MockitoBean HttpGateway http;
    private AtomicLong now;
    private Clock clock;
    private LocalAuth auth;
    private String owner;

    @BeforeEach void setup() {
        now = new AtomicLong(System.currentTimeMillis());
        clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        auth = service(environment);
        owner = store.db.queryForObject("SELECT user_id FROM local_login WHERE id='owner'",String.class);
    }

    private LocalAuth service(Environment env) {
        var result = new LocalAuth(settings,store,tx,env,clock);
        result.initialize();
        return result;
    }
    private MockEnvironment environment(String username,String email,String encodedHash) {
        return new MockEnvironment().withProperty("LOCAL_LOGIN_USERNAME",username)
            .withProperty("LOCAL_LOGIN_EMAIL",email).withProperty("LOCAL_LOGIN_PASSWORD_HASH_BASE64",encodedHash)
            .withProperty("COOKIE_SECURE","true");
    }
    private MockHttpServletRequest request(Cookie... cookies) {
        var request = new MockHttpServletRequest();
        request.setScheme("https");request.setServerName("ctfmon.shop");request.setServerPort(443);
        request.setCookies(cookies);return request;
    }
    private Cookie rememberCookie(MockHttpServletResponse response) {
        String header = response.getHeaders("Set-Cookie").stream().filter(value -> value.startsWith(LocalAuth.COOKIE+"="))
            .findFirst().orElseThrow();
        return new Cookie(LocalAuth.COOKIE,header.substring(header.indexOf('=')+1,header.indexOf(';')));
    }
    private int tokenCount() { return store.db.queryForObject("SELECT COUNT(*) FROM remembered_login",Integer.class); }
    private void rejects(int status,org.junit.jupiter.api.function.Executable action) {
        assertEquals(status,assertThrows(ApiException.class,action).status);
    }
    private MvcResult session() throws Exception { return mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn(); }
    private MvcResult loginHttp(MvcResult before,boolean remember) throws Exception {
        var data=Json.read(before.getResponse().getContentAsString());
        return mvc.perform(post("/api/auth/password").session((MockHttpSession)before.getRequest().getSession())
            .header(Json.str(data,"csrfHeader"),Json.str(data,"csrfToken")).contentType("application/json")
            .content(Json.write(Map.of("username",USERNAME,"password",PASSWORD,"rememberMe",remember))))
            .andExpect(status().isOk()).andReturn();
    }

    @Test void httpPasswordLoginRequiresCsrfAndReturnsAccountMetadata() throws Exception {
        mvc.perform(post("/api/auth/password").contentType("application/json")
            .content(Json.write(Map.of("username",USERNAME,"password",PASSWORD)))).andExpect(status().isForbidden());
        var initial=session();
        var initialData=Json.read(initial.getResponse().getContentAsString());
        assertTrue(initialData.path("passwordLoginConfigured").asBoolean());
        assertFalse(initialData.path("googleConnected").asBoolean());
        String previousId=initial.getRequest().getSession().getId();

        var login=loginHttp(initial,false);

        assertNotEquals(previousId,login.getRequest().getSession().getId());
        assertNull(login.getRequest().getSession().getAttribute(LocalAuth.CSRF_TOKEN));
        assertEquals(0,tokenCount());
        mvc.perform(get("/api/session").session((MockHttpSession)login.getRequest().getSession()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(owner))
            .andExpect(jsonPath("$.googleConnected").value(false)).andExpect(jsonPath("$.remembered").value(false));
        verifyNoInteractions(http);
    }

    @Test void httpRememberCookieRestoresBeforeCsrfAndLogoutRevokesIt() throws Exception {
        var login=loginHttp(session(),true);
        Cookie cookie=rememberCookie(login.getResponse());
        String header=login.getResponse().getHeader("Set-Cookie");
        assertTrue(header.contains("HttpOnly"));assertTrue(header.contains("Secure"));
        assertTrue(header.contains("SameSite=Lax"));assertTrue(header.contains("Max-Age=2592000"));
        assertFalse(header.contains("Domain="));
        assertEquals(43,cookie.getValue().length());
        String stored=store.db.queryForObject("SELECT token_hash FROM remembered_login",String.class);
        assertEquals(64,stored.length());assertNotEquals(cookie.getValue(),stored);
        var anonymous=new MockHttpSession();String previousId=anonymous.getId();

        var restored=mvc.perform(get("/api/session").cookie(cookie).session(anonymous)).andExpect(status().isOk())
            .andExpect(jsonPath("$.user.id").value(owner)).andExpect(jsonPath("$.remembered").value(true)).andReturn();

        assertNotEquals(previousId,restored.getRequest().getSession().getId());
        var data=Json.read(restored.getResponse().getContentAsString());
        mvc.perform(post("/api/auth/logout").cookie(cookie).session((MockHttpSession)restored.getRequest().getSession())
            .header(Json.str(data,"csrfHeader"),Json.str(data,"csrfToken")))
            .andExpect(status().isOk()).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertEquals(0,tokenCount());
        mvc.perform(get("/api/session").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.user").doesNotExist()).andExpect(jsonPath("$.remembered").value(false));
        verifyNoInteractions(http);
    }

    @Test void unauthenticatedDataAccessStaysProtected() throws Exception {
        mvc.perform(get("/api/calendars")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/calendars").cookie(new Cookie(LocalAuth.COOKIE,"wrong-cookie")))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(http);
    }

    @Test void bootstrapPreservesExistingGoogleAccountAndCalendarData() {
        String email="existing-google@example.com",id="google-"+UUID.randomUUID();
        store.db.update("INSERT INTO app_user(id,email,display_name,refresh_token,calendar_id,google_subject) VALUES(?,?,?,?,?,?)",
            id,email,"Existing","encrypted-existing-token","existing-calendar",id);
        String eventId=UUID.randomUUID().toString();
        store.db.update("INSERT INTO calendar_event(id,user_id,google_id,payload,sync_state) VALUES(?,?,?,?,?)",
            eventId,id,"existing-event","{}","SYNCED");
        var config=new AppConfig.Settings(settings.baseUrl(),false,email,settings.encryptionKey(),settings.googleClientId(),
            settings.googleClientSecret(),settings.geminiApiKey(),settings.geminiModel(),settings.ffmpeg());
        var existing=new LocalAuth(config,store,tx,environment(USERNAME,email,ENCODED_HASH),clock);

        existing.initialize();
        var request=request();existing.login(USERNAME,PASSWORD,false,request,new MockHttpServletResponse());

        assertEquals(id,request.getSession().getAttribute("uid"));
        assertEquals(1,store.events(id).size());
        assertEquals("existing-calendar",store.user(id).calendarId());
        assertEquals("encrypted-existing-token",store.user(id).refreshToken());
        assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM app_user WHERE email=?",Integer.class,email));
    }

    @Test void freshAccountCanLogInBeforeGoogleIsConnected() {
        assertTrue(owner.startsWith("local-"));
        assertNull(store.user(owner).refreshToken());
        var request=request();var response=new MockHttpServletResponse();

        auth.login(USERNAME,PASSWORD,null,request,response);

        assertEquals(owner,request.getSession().getAttribute("uid"));
        assertEquals(1,tokenCount());
        assertTrue(auth.remembered(request.getSession()));
    }

    @Test void rememberSurvivesNewServiceInstanceAndExpiresAfterThirtyDays() {
        var login=request();var response=new MockHttpServletResponse();
        auth.login(USERNAME,PASSWORD,true,login,response);Cookie cookie=rememberCookie(response);
        LocalAuth restarted=service(environment);
        var restored=request(cookie);

        restarted.restore(restored,new MockHttpServletResponse());

        assertEquals(owner,restored.getSession().getAttribute("uid"));
        now.addAndGet(Duration.ofDays(30).toMillis());
        var oldSession=(MockHttpSession)restored.getSession();
        restarted.restore(restored,new MockHttpServletResponse());
        assertTrue(oldSession.isInvalid());
        var expired=request(cookie);var expiredResponse=new MockHttpServletResponse();
        restarted.restore(expired,expiredResponse);
        assertNull(expired.getSession(false));assertEquals(0,tokenCount());
        assertTrue(expiredResponse.getHeader("Set-Cookie").contains("Max-Age=0"));
    }

    @Test void logoutRevokesOnlyCurrentDevice() {
        var first=request();var firstResponse=new MockHttpServletResponse();
        var second=request();var secondResponse=new MockHttpServletResponse();
        auth.login(USERNAME,PASSWORD,true,first,firstResponse);
        auth.login(USERNAME,PASSWORD,true,second,secondResponse);
        Cookie firstCookie=rememberCookie(firstResponse),secondCookie=rememberCookie(secondResponse);
        assertEquals(2,tokenCount());

        auth.logout(first,new MockHttpServletResponse());

        assertEquals(1,tokenCount());
        var revoked=request(firstCookie);auth.restore(revoked,new MockHttpServletResponse());
        assertNull(revoked.getSession(false));
        var remaining=request(secondCookie);auth.restore(remaining,new MockHttpServletResponse());
        assertEquals(owner,remaining.getSession().getAttribute("uid"));
    }

    @Test void changedPasswordRevisionRevokesRememberedAndSessionOnlyLogins() {
        var remembered=request();var response=new MockHttpServletResponse();
        var sessionOnly=request();
        auth.login(USERNAME,PASSWORD,true,remembered,response);
        auth.login(USERNAME,PASSWORD,false,sessionOnly,new MockHttpServletResponse());
        var rememberedSession=(MockHttpSession)remembered.getSession();
        var nonRememberedSession=(MockHttpSession)sessionOnly.getSession();
        Cookie cookie=rememberCookie(response);
        String changedPassword="changed-"+UUID.randomUUID();
        String changedHash=new BCryptPasswordEncoder(12).encode(changedPassword);
        LocalAuth changed=service(environment(USERNAME,EMAIL,Base64.getEncoder().encodeToString(changedHash.getBytes(StandardCharsets.US_ASCII))));

        assertEquals(0,tokenCount());
        changed.restore(remembered,new MockHttpServletResponse());
        changed.restore(sessionOnly,new MockHttpServletResponse());
        assertTrue(rememberedSession.isInvalid());assertTrue(nonRememberedSession.isInvalid());
        var oldBrowser=request(cookie);changed.restore(oldBrowser,new MockHttpServletResponse());
        assertNull(oldBrowser.getSession(false));
        rejects(401,() -> changed.login(USERNAME,PASSWORD,false,request(),new MockHttpServletResponse()));
        var newBrowser=request();changed.login(USERNAME,changedPassword,false,newBrowser,new MockHttpServletResponse());
        assertEquals(owner,newBrowser.getSession().getAttribute("uid"));
    }

    @Test void invalidCredentialsHaveSameMessageAndPasswordsAreLengthLimited() {
        var wrongUser=assertThrows(ApiException.class,() -> auth.login("unknown-user",PASSWORD,false,request(),new MockHttpServletResponse()));
        var wrongPassword=assertThrows(ApiException.class,() -> auth.login(USERNAME,"wrong-test-password",false,request(),new MockHttpServletResponse()));
        assertEquals(401,wrongUser.status);assertEquals(401,wrongPassword.status);
        assertEquals(wrongUser.getMessage(),wrongPassword.getMessage());
        rejects(401,() -> auth.login(USERNAME,"a".repeat(73),false,request(),new MockHttpServletResponse()));
        rejects(401,() -> auth.login(USERNAME,"가".repeat(25),false,request(),new MockHttpServletResponse()));
        assertEquals(0,tokenCount());
    }

    @Test void sessionUsernameAndGlobalRateLimitsExpire() {
        var sameSession=request();
        for(int i=0;i<5;i++) rejects(401,() -> auth.login(USERNAME,"",false,sameSession,new MockHttpServletResponse()));
        rejects(429,() -> auth.login(USERNAME,"",false,sameSession,new MockHttpServletResponse()));
        var perUsername=service(environment);
        for(int i=0;i<10;i++) rejects(401,() -> perUsername.login(USERNAME,"",false,request(),new MockHttpServletResponse()));
        rejects(429,() -> perUsername.login(USERNAME,"",false,request(),new MockHttpServletResponse()));
        var global=service(environment);
        for(int i=0;i<60;i++) {
            String username="attempt-"+i;
            rejects(401,() -> global.login(username,"",false,request(),new MockHttpServletResponse()));
        }
        rejects(429,() -> global.login("another-user","",false,request(),new MockHttpServletResponse()));
        now.addAndGet(60_000);
        rejects(401,() -> auth.login(USERNAME,"",false,sameSession,new MockHttpServletResponse()));
        rejects(401,() -> perUsername.login(USERNAME,"",false,request(),new MockHttpServletResponse()));
        rejects(401,() -> global.login("another-user","",false,request(),new MockHttpServletResponse()));
    }

    @Test void rememberCannotReplaceAnotherSignedInAccountAndTamperingFails() {
        var login=request();var response=new MockHttpServletResponse();
        auth.login(USERNAME,PASSWORD,true,login,response);Cookie cookie=rememberCookie(response);
        var other=request(cookie);other.getSession().setAttribute("uid","other-user");

        auth.restore(other,new MockHttpServletResponse());

        assertEquals("other-user",other.getSession().getAttribute("uid"));
        String token=cookie.getValue();String modified=(token.charAt(0)=='A'?"B":"A")+token.substring(1);
        var attacker=request(new Cookie(LocalAuth.COOKIE,modified));auth.restore(attacker,new MockHttpServletResponse());
        assertNull(attacker.getSession(false));
        assertEquals(1,tokenCount());
        rejects(409,() -> auth.login(USERNAME,PASSWORD,true,other,new MockHttpServletResponse()));
    }

    @Test void removingPasswordConfigurationRevokesPersistentAndPasswordSessions() {
        var login=request();var response=new MockHttpServletResponse();
        auth.login(USERNAME,PASSWORD,true,login,response);
        var prior=(MockHttpSession)login.getSession();
        var disabled=service(new MockEnvironment());

        disabled.restore(login,new MockHttpServletResponse());

        assertFalse(disabled.configured());assertTrue(prior.isInvalid());assertEquals(0,tokenCount());
    }
}
