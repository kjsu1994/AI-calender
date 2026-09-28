package kr.dayvoice;

import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import static kr.dayvoice.HttpGateway.enc;

@Service
class SyncService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SyncService.class);
    private final Map<String,String> errors=new java.util.concurrent.ConcurrentHashMap<>();
    String error(String user) { return errors.getOrDefault(user, ""); }
    private final Store store; private final GoogleCalendar google; private final TransactionTemplate tx;
    SyncService(Store store,GoogleCalendar google,TransactionTemplate tx) {this.store=store;this.google=google;this.tx=tx;}
    @Scheduled(fixedDelay=5000,initialDelay=5000)
    public void sendPending() {
        var users=store.db.queryForList("SELECT DISTINCT e.user_id FROM outbox o JOIN calendar_event e ON e.id=o.event_id JOIN app_user u ON u.id=e.user_id WHERE u.demo=FALSE AND u.sync_status<>'REAUTH' AND e.sync_state<>'CONFLICT' AND o.next_attempt<=?",String.class,System.currentTimeMillis());
        for(String user:users) {
            try {
                tx.executeWithoutResult(t -> {
                    store.lock(user);var u=store.user(user);
                    push(u,google.token(u));
                    int problems=store.db.queryForObject("SELECT COUNT(*) FROM calendar_event WHERE user_id=? AND sync_state IN ('ERROR','CONFLICT')",Integer.class,user);
                    if(problems>0)store.db.update("UPDATE app_user SET sync_status='ATTENTION' WHERE id=?",user);
                });
            } catch(ApiException e) {
                errors.put(user,e.getMessage()); safeFailure("push",e);
                store.db.update("UPDATE app_user SET sync_status=? WHERE id=?",e.status==401?"REAUTH":"ERROR",user);
                store.db.update("UPDATE outbox SET next_attempt=? WHERE event_id IN (SELECT id FROM calendar_event WHERE user_id=?)",System.currentTimeMillis()+60000,user);
            } catch(Exception e) {errors.put(user,"일정을 전송하지 못했습니다. 잠시 후 다시 동기화해 주세요.");safeFailure("push",e);store.db.update("UPDATE app_user SET sync_status='ERROR' WHERE id=?",user);}
        }
    }
    @Scheduled(fixedDelay=60000,initialDelay=15000)
    public void tick() {
        var ids=store.db.queryForList("SELECT id FROM app_user WHERE demo=FALSE AND calendar_id IS NOT NULL AND sync_status<>'REAUTH'",String.class);
        for(String id:ids) { try {sync(id,false);} catch(Exception ignored) { /* Status is exposed to the user; never log tokens. */ } }
        store.db.update("DELETE FROM voice_command WHERE expires_at<?",System.currentTimeMillis()-86400000L);
    }
    public void sync(String user,boolean force) {
        try {
            tx.executeWithoutResult(t -> {
                store.lock(user);var u=store.user(user);
                if(u.demo() || u.calendarId()==null) return;
                String token=google.token(u);
                if(force) store.db.update("UPDATE outbox SET next_attempt=0 WHERE event_id IN (SELECT id FROM calendar_event WHERE user_id=?)",user);
                pull(u,token,false);
                push(store.user(user),token);
                recurring(store.user(user),token);
                int problems=store.db.queryForObject("SELECT COUNT(*) FROM calendar_event WHERE user_id=? AND sync_state IN ('ERROR','CONFLICT')",Integer.class,user);
                store.db.update("UPDATE app_user SET last_sync=?,sync_status=? WHERE id=?",Instant.now().toString(),problems>0?"ATTENTION":"SYNCED",user);
                if(problems==0) errors.remove(user);
                org.slf4j.LoggerFactory.getLogger(SyncService.class).info("Google Calendar sync completed; items={}, issues={}",store.events(user).stream().filter(e->!e.deleted()).count(),problems);
            });
        } catch(ApiException e) {
            errors.put(user,e.getMessage());safeFailure("sync",e);
            store.db.update("UPDATE app_user SET sync_status=? WHERE id=?",e.status==401?"REAUTH":"ERROR",user);throw e;
        } catch(Exception e) {
            errors.put(user,"동기화 처리 중 오류가 발생했습니다. 다시 동기화해 주세요.");safeFailure("sync",e);
            store.db.update("UPDATE app_user SET sync_status='ERROR' WHERE id=?",user);throw new ApiException(503,"동기화하지 못했습니다. 다시 시도해 주세요.");
        }
    }
    private void pull(Store.User u,String token,boolean reset) {
        String page="",sync=reset?null:u.syncToken();Set<String> seen=new HashSet<>();
        do {
            String path=google.path(u)+"?maxResults=250&showDeleted=true"+(sync==null?"":"&syncToken="+enc(sync))+(page.isEmpty()?"":"&pageToken="+enc(page));
            var r=google.call(token,"GET",path,null,null);
            if(r.status()==410 && !reset) {pull(u,token,true);return;}
            check(r);
            for(var item:r.body().path("items")) {
                if(item.has("recurrence") || item.has("recurringEventId")) continue;
                seen.add(Json.str(item,"id")); ingest(u,item,false);
            }
            page=Json.str(r.body(),"nextPageToken");
            if(page.isEmpty()) store.db.update("UPDATE app_user SET sync_token=? WHERE id=?",Json.str(r.body(),"nextSyncToken"),u.id());
        } while(!page.isEmpty());
        if(sync==null) {
            for(var e:store.events(u.id())) if(!e.recurring() && e.etag()!=null && !seen.contains(e.googleId()) && !e.deleted())
                ingest(u,Json.object().put("id",e.googleId()).put("status","cancelled"),false);
        }
    }
    private void recurring(Store.User u,String token) {
        LocalDate today=LocalDate.now(Events.ZONE); String page="";Set<String> seen=new HashSet<>();
        do {
            var r=google.call(token,"GET",google.path(u)+"?singleEvents=true&maxResults=250&timeMin="+enc(today.minusDays(30).atStartOfDay(Events.ZONE).toOffsetDateTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))+
                "&timeMax="+enc(today.plusDays(366).atStartOfDay(Events.ZONE).toOffsetDateTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))+(page.isEmpty()?"":"&pageToken="+enc(page)),null,null);
            check(r);
            for(var g:r.body().path("items")) if(g.has("recurringEventId")) {seen.add(Json.str(g,"id"));ingest(u,g,true);}
            page=Json.str(r.body(),"nextPageToken");
        } while(!page.isEmpty());
        for(var e:store.events(u.id())) if(e.recurring() && !seen.contains(e.googleId())) store.db.update("DELETE FROM calendar_event WHERE id=?",e.id());
    }
    void ingest(Store.User u,JsonNode item,boolean recurring) {
        String gid=Json.str(item,"id"),etag=Json.str(item,"etag"); boolean deleted=Json.str(item,"status").equals("cancelled");
        var existing=store.events(u.id()).stream().filter(e->e.googleId().equals(gid)).findFirst();
        if(existing.isEmpty()) {
            if(deleted || !item.has("start")) return;
            store.db.update("INSERT INTO calendar_event(id,user_id,google_id,etag,payload,sync_state,recurring) VALUES(?,?,?,?,?,'SYNCED',?)",UUID.randomUUID().toString(),u.id(),gid,etag,Json.write(GoogleCalendar.fromGoogle(item)),recurring);return;
        }
        var e=existing.get();
        if(Objects.equals(e.etag(),etag) && !deleted) return;
        JsonNode remote=deleted?Json.object().put("deleted",true):GoogleCalendar.fromGoogle(item);
        boolean pending=List.of("PENDING","ERROR","CONFLICT").contains(e.syncState());
        // A previous write may have succeeded before a connection loss. Acknowledge matching state.
        if(pending && ((deleted && e.deleted()) || (!deleted && !e.deleted() && e.data().equals(remote)))) {
            store.db.update("UPDATE calendar_event SET sync_state='SYNCED',etag=?,remote_payload=NULL WHERE id=?",etag,e.id());
            store.db.update("DELETE FROM outbox WHERE event_id=?",e.id());return;
        }
        if(pending) {
            store.db.update("UPDATE calendar_event SET sync_state='CONFLICT',remote_payload=?,etag=?,version=version+1 WHERE id=?",Json.write(remote),etag,e.id());
        } else {
            store.db.update("UPDATE calendar_event SET payload=?,deleted=?,etag=?,sync_state='SYNCED',version=version+1 WHERE id=?",Json.write(deleted?e.data():remote),deleted,etag,e.id());
        }
    }
    private void push(Store.User u,String token) {
        var jobs=store.db.queryForList("SELECT o.* FROM outbox o JOIN calendar_event e ON e.id=o.event_id WHERE e.user_id=? AND e.sync_state<>'CONFLICT' AND o.next_attempt<=?",u.id(),System.currentTimeMillis());
        for(var job:jobs) {
            String id=job.get("event_id").toString(),op=job.get("operation").toString();var e=store.event(u.id(),id);
            boolean create=op.equals("CREATE"),delete=op.equals("DELETE");
            HttpGateway.Reply r;
            try {
                r=google.call(token,create?"POST":delete?"DELETE":"PATCH",google.path(u)+(create?"":"/"+enc(e.googleId())),delete?null:GoogleCalendar.toGoogle(e,create),create?null:e.etag());
            } catch(ApiException failure) {
                retry(id, ((Number)job.get("attempts")).intValue());
                continue;
            }
            if(r.status()==401) throw new ApiException(401,"Google 계정을 다시 연결해 주세요.");
            if((r.status()>=200 && r.status()<300) || (delete && (r.status()==404 || r.status()==410))) {
                store.db.update("UPDATE calendar_event SET sync_state='SYNCED',etag=?,remote_payload=NULL WHERE id=?",delete?e.etag():Json.str(r.body(),"etag"),id);
                store.db.update("DELETE FROM outbox WHERE event_id=?",id);
            } else if(r.status()==409 || r.status()==412 || r.status()==404 || r.status()==410) {
                var current=google.call(token,"GET",google.path(u)+"/"+enc(e.googleId()),null,null);
                if(current.status()==200) ingest(u,current.body(),false);
                else if(current.status()==404 || current.status()==410) ingest(u,Json.object().put("id",e.googleId()).put("status","cancelled"),false);
                else check(current);
            } else {
                retry(id, ((Number)job.get("attempts")).intValue());
            }
        }
    }
    private void retry(String id,int previousAttempts) {
        int attempts=previousAttempts+1;
        store.db.update("UPDATE outbox SET attempts=?,next_attempt=? WHERE event_id=?",attempts,System.currentTimeMillis()+Math.min(3600000L,15000L*(1L<<Math.min(attempts,8))),id);
        store.db.update("UPDATE calendar_event SET sync_state='ERROR' WHERE id=?",id);
    }
    private void check(HttpGateway.Reply r) {
        if(r.status()==200) return;
        String reason=r.body().path("error").path("errors").path(0).path("reason").asText("");
        if(!reason.matches("[A-Za-z_]{1,80}"))reason="unknown";
        log.warn("Google Calendar response failed: status={}, reason={}",r.status(),reason);
        String message=switch(r.status()) {
            case 401 -> "Google 계정을 다시 연결해 주세요.";
            case 403 -> "Google 캘린더 접근 권한을 확인해 주세요. Calendar API 활성화와 계정 연결 시 일정 권한 동의가 필요합니다.";
            case 404 -> "연결한 Google 캘린더를 찾을 수 없습니다. 캘린더 접근 권한을 확인해 주세요.";
            case 429 -> "Google 요청 한도에 도달했습니다. 잠시 후 다시 동기화해 주세요.";
            default -> "Google 캘린더 동기화에 실패했습니다. 잠시 후 다시 시도해 주세요.";
        };
        throw new ApiException(r.status()==401?401:503,message);
    }
    private void safeFailure(String phase,Exception failure) {
        Throwable cause=failure.getCause()==null?failure:failure.getCause();
        String frames=Arrays.stream(cause.getStackTrace()).limit(4).map(f->f.getClassName()+":"+f.getLineNumber()).collect(java.util.stream.Collectors.joining(","));
        log.warn("Calendar {} failed: type={}, cause={}, frames={}",phase,failure.getClass().getSimpleName(),cause.getClass().getSimpleName(),frames);
    }
}
