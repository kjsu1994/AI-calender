package kr.dayvoice;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import static kr.dayvoice.HttpGateway.enc;

@RestController
class AuthController {
    private final AppConfig.Settings cfg; private final Store store;
    private final HttpGateway http; private final TransactionTemplate tx; private final SyncService sync;
    private final LocalAuth localAuth; private final GoogleAccountLinker accountLinker;
    AuthController(AppConfig.Settings cfg, Store store, HttpGateway http, TransactionTemplate tx, SyncService sync,
        LocalAuth localAuth, GoogleAccountLinker accountLinker) {
        this.cfg=cfg; this.store=store; this.http=http; this.tx=tx; this.sync=sync;
        this.localAuth=localAuth; this.accountLinker=accountLinker;
    }
    static String uid(HttpSession session) {
        Object id = session.getAttribute("uid");
        if (id == null) throw new ApiException(401,"로그인해 주세요.");
        return id.toString();
    }
    @GetMapping("/api/health") Map<String,Object> health() { store.db.queryForObject("SELECT 1", Integer.class); return Map.of("status","UP"); }
    @GetMapping("/api/session") Map<String,Object> session(HttpServletRequest req, CsrfToken csrf) {
        var result = new LinkedHashMap<String,Object>();
        result.put("csrfToken", csrf.getToken()); result.put("csrfHeader", csrf.getHeaderName());
        result.put("publicBaseUrl", cfg.baseUrl().replaceAll("/+$", ""));
        result.put("demoMode", cfg.demoMode()); result.put("geminiConfigured", !cfg.geminiApiKey().isBlank());
        result.put("googleConfigured", !cfg.googleClientId().isBlank() && !cfg.googleClientSecret().isBlank());
        result.put("passwordLoginConfigured", localAuth.configured());
        result.put("googleConnected", false);
        result.put("remembered", localAuth.remembered(req.getSession(false)));
        Object id = req.getSession().getAttribute("uid");
        if (id != null) {
            Store.User u=store.user(id.toString());
            result.put("user", Map.of("id",u.id(),"name",u.name(),"email",u.email(),"demo",u.demo()));
            result.put("googleConnected", u.refreshToken()!=null && !u.refreshToken().isBlank());
            result.put("calendarId",u.calendarId()); result.put("calendarName",u.calendarName());
            result.put("syncStatus",u.syncStatus()); result.put("lastSync",u.lastSync());
            result.put("syncError",sync.error(u.id()));
        } else result.put("user",null);
        return result;
    }
    record PasswordRequest(String username, String password, Boolean rememberMe) {}
    @PostMapping("/api/auth/password") Map<String,Object> password(@RequestBody PasswordRequest body,
        HttpServletRequest req, HttpServletResponse res) {
        localAuth.login(body.username(),body.password(),body.rememberMe(),req,res);
        return Map.of("ok",true);
    }
    @GetMapping("/api/auth/google") void login(HttpServletRequest req, HttpServletResponse res) throws Exception {
        if (cfg.demoMode()) throw new ApiException(400,"실제 Google 연동은 DEMO_MODE=false로 변경한 뒤 사용해 주세요.");
        if (cfg.googleClientId().isBlank() || cfg.googleClientSecret().isBlank() || cfg.allowedEmails().isBlank())
            throw new ApiException(503,".env에 Google OAuth 설정과 ALLOWED_EMAILS를 입력해 주세요.");
        String state=random(), verifier=random();
        req.getSession().setAttribute("oauthState",state); req.getSession().setAttribute("oauthVerifier",verifier);
        req.getSession().setAttribute("oauthTime",System.currentTimeMillis());
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        res.sendRedirect("https://accounts.google.com/o/oauth2/v2/auth?client_id="+enc(cfg.googleClientId())+
            "&redirect_uri="+enc(callback())+"&response_type=code&scope="+enc("openid email profile https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.calendarlist.readonly")+
            "&access_type=offline&prompt=consent&state="+state+"&code_challenge_method=S256&code_challenge="+challenge);
    }
    @GetMapping("/api/auth/google/callback") void callback(@RequestParam(required=false) String code,
        @RequestParam(required=false) String state, HttpServletRequest req, HttpServletResponse res) throws Exception {
        HttpSession s=req.getSession(); Object expected=s.getAttribute("oauthState"), time=s.getAttribute("oauthTime"), verifier=s.getAttribute("oauthVerifier");
        s.removeAttribute("oauthState"); s.removeAttribute("oauthTime"); s.removeAttribute("oauthVerifier");
        if (cfg.demoMode() || code==null || state==null || expected==null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),expected.toString().getBytes(StandardCharsets.UTF_8)) || time==null || System.currentTimeMillis()-(long)time>600000)
            { res.sendRedirect(publicUrl("/?authError=invalid")); return; }
        var token=http.form("https://oauth2.googleapis.com/token",Map.of("code",code,"client_id",cfg.googleClientId(),
            "client_secret",cfg.googleClientSecret(),"redirect_uri",callback(),"grant_type","authorization_code","code_verifier",verifier.toString()));
        if(token.status()!=200) { res.sendRedirect(publicUrl("/?authError=token")); return; }
        var info=http.send("GET","https://openidconnect.googleapis.com/v1/userinfo",null,Map.of("Authorization","Bearer "+Json.str(token.body(),"access_token")));
        String email=Json.str(info.body(),"email").toLowerCase(Locale.ROOT), sub=Json.str(info.body(),"sub");
        boolean allowed=Arrays.stream(cfg.allowedEmails().split(",")).map(String::trim).anyMatch(e -> e.equalsIgnoreCase(email));
        if(info.status()!=200 || !info.body().path("email_verified").asBoolean() || !allowed || sub.isBlank()) {res.sendRedirect(publicUrl("/?authError=denied"));return;}
        String refresh=Json.str(token.body(),"refresh_token");
        String userId;
        try {
            userId=accountLinker.link(Objects.toString(s.getAttribute("uid"),null),sub,email,Json.str(info.body(),"name"),refresh);
        } catch(ApiException failure) {
            if(failure.status==403) {res.sendRedirect(publicUrl("/?authError=denied"));return;}
            throw failure;
        }
        req.changeSessionId(); s.setAttribute("uid",userId); s.removeAttribute(LocalAuth.CSRF_TOKEN);
        org.slf4j.LoggerFactory.getLogger(AuthController.class).info("Google OAuth sign-in completed successfully");
        res.sendRedirect(publicUrl("/"));
    }
    @PostMapping("/api/auth/demo") Map<String,Object> demo(HttpServletRequest req) {
        if(!cfg.demoMode()) throw new ApiException(404,"샘플 모드가 아닙니다.");
        Object previous=req.getSession().getAttribute("uid");
        if(previous!=null) return Map.of("ok",true);
        String id="demo-"+UUID.randomUUID();
        tx.executeWithoutResult(t -> {
            store.db.update("INSERT INTO app_user(id,email,display_name,calendar_id,calendar_name,sync_status,demo) VALUES(?,?,?,?,?,'DEMO',TRUE)",id,"sample@dayvoice.local","나의 하루","demo","샘플 캘린더");
            LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
            for(int i=0;i<3;i++) {
                String eid=UUID.randomUUID().toString();
                var data=Json.object().put("title",List.of("가볍게 시작하는 아침","프로젝트 아이디어 정리","저녁 산책").get(i))
                    .put("start",today.atTime(9+i*4,0).atOffset(ZoneOffset.ofHours(9)).toString())
                    .put("end",today.atTime(10+i*4,0).atOffset(ZoneOffset.ofHours(9)).toString())
                    .put("allDay",false).put("description","").put("location","");
                store.db.update("INSERT INTO calendar_event(id,user_id,google_id,payload,sync_state) VALUES(?,?,?,?,'LOCAL')",eid,id,eid,Json.write(data));
            }
        });
        req.changeSessionId(); req.getSession().setAttribute("uid",id); return Map.of("ok",true);
    }
    @PostMapping("/api/auth/logout") Map<String,Object> logout(HttpServletRequest req,HttpServletResponse res) {
        localAuth.logout(req,res);return Map.of("ok",true);
    }
    private String publicUrl(String path) { return cfg.baseUrl().replaceAll("/+$", "")+path; }
    private String callback() { return publicUrl("/api/auth/google/callback"); }
    private static String random() { byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
