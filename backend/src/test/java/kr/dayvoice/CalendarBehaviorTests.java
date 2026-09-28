package kr.dayvoice;

import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class CalendarBehaviorTests {
    @Autowired Store store; @Autowired Commands commands; @Autowired Events events; @Autowired Crypto crypto; @Autowired SyncService sync;
    @MockitoBean Gemini gemini;
    String user;
    @BeforeEach void setup() {
        user=UUID.randomUUID().toString();
        store.db.update("INSERT INTO app_user(id,email,display_name,calendar_id,demo) VALUES(?,?,?,?,TRUE)",user,"test@example.com","Test","demo");
    }
    private com.fasterxml.jackson.databind.JsonNode data(String title) {
        return Json.read("{\"title\":\""+title+"\",\"start\":\"2026-09-25T15:00:00+09:00\",\"end\":\"\",\"allDay\":false}");
    }
    @Test void creationIsIdempotentAndDefaultsToOneHour() {
        String request=UUID.randomUUID().toString();
        Object a=commands.manual(user,request,"CREATE","",0,data("치과"));
        Object b=commands.manual(user,request,"CREATE","",0,data("치과"));
        assertEquals(Json.read(Json.write(a)),Json.read(Json.write(b)));assertEquals(1,store.events(user).size());
        assertEquals("2026-09-25T16:00+09:00",Json.str(store.events(user).getFirst().data(),"end"));
        assertThrows(ApiException.class,()->commands.manual(user,request,"CREATE","",0,data("다른 일정")));
    }
    @Test void mutationNeedsConfirmationAndStaleConfirmationFails() {
        var e=events.create(user,data("원래 제목"));
        var proposal=Json.read(Json.write(commands.manual(user,UUID.randomUUID().toString(),"UPDATE",e.id(),e.version(),data("새 제목"))));
        assertEquals("원래 제목",Json.str(store.event(user,e.id()).data(),"title"));
        commands.finish(user,Json.str(proposal,"commandId"),true);
        assertEquals("새 제목",Json.str(store.event(user,e.id()).data(),"title"));
        commands.finish(user,Json.str(proposal,"commandId"),true); // replay never mutates again
        assertEquals(2,store.event(user,e.id()).version());
        var stale=Json.read(Json.write(commands.manual(user,UUID.randomUUID().toString(),"DELETE",e.id(),2,Json.object())));
        events.change(user,e.id(),2,data("동시 수정"),false);
        assertThrows(ApiException.class,()->commands.finish(user,Json.str(stale,"commandId"),true));
    }
    @Test void cancelledAndExpiredConfirmationsDoNotChangeEvent() {
        var e=events.create(user,data("보존"));
        var p=Json.read(Json.write(commands.manual(user,UUID.randomUUID().toString(),"DELETE",e.id(),1,Json.object())));
        commands.finish(user,Json.str(p,"commandId"),false); commands.finish(user,Json.str(p,"commandId"),true);
        assertFalse(store.event(user,e.id()).deleted());
        var q=Json.read(Json.write(commands.manual(user,UUID.randomUUID().toString(),"DELETE",e.id(),1,Json.object())));
        store.db.update("UPDATE voice_command SET expires_at=0 WHERE id=?",Json.str(q,"commandId"));
        assertThrows(ApiException.class,()->commands.finish(user,Json.str(q,"commandId"),true));
    }
    @Test void userIsolationAndRecurringReadOnly() {
        var e=events.create(user,data("비공개"));
        assertThrows(ApiException.class,()->store.event("someone-else",e.id()));
        store.db.update("UPDATE calendar_event SET recurring=TRUE WHERE id=?",e.id());
        assertThrows(ApiException.class,()->commands.manual(user,UUID.randomUUID().toString(),"DELETE",e.id(),1,Json.object()));
    }
    @Test void allDayExclusiveEndAndMidnightAreCorrect() {
        var d=Json.read("{\"title\":\"휴가\",\"start\":\"2026-09-25\",\"end\":\"2026-09-27\",\"allDay\":true}");
        events.create(user,d);
        assertEquals(1,events.list(user,LocalDate.parse("2026-09-26"),LocalDate.parse("2026-09-27")).size());
        assertEquals(0,events.list(user,LocalDate.parse("2026-09-27"),LocalDate.parse("2026-09-28")).size());
        assertThrows(ApiException.class,()->Events.validate(Json.read("{\"title\":\"잘못된 일정\",\"start\":\"2026-09-25\",\"end\":\"2026-09-25\",\"allDay\":true}")));
    }
    @Test void realAccountWritesOutboxAtomicallyAndUnacknowledgedCreateDeletionIsQueued() {
        store.db.update("UPDATE app_user SET demo=FALSE WHERE id=?",user);
        var e=events.create(user,data("전송"));
        assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_id=?",Integer.class,e.id()));
        events.change(user,e.id(),1,Json.object(),true);
        assertEquals("DELETE",store.db.queryForObject("SELECT operation FROM outbox WHERE event_id=?",String.class,e.id()));
    }
    @Test void externalChangesConflictWithoutOverwritingLocalAndCanBeResolved() {
        store.db.update("UPDATE app_user SET demo=FALSE WHERE id=?",user);
        var e=events.create(user,data("내 변경"));
        var remote=Json.read("{\"id\":\""+e.googleId()+"\",\"etag\":\"etag2\",\"summary\":\"구글 변경\",\"start\":{\"dateTime\":\"2026-09-25T15:00:00+09:00\"},\"end\":{\"dateTime\":\"2026-09-25T16:00:00+09:00\"}}");
        sync.ingest(store.user(user),remote,false);
        var conflict=store.event(user,e.id());
        assertEquals("CONFLICT",conflict.syncState());assertEquals("내 변경",Json.str(conflict.data(),"title"));
        events.resolve(user,e.id(),"remote",conflict.version());
        assertEquals("구글 변경",Json.str(store.event(user,e.id()).data(),"title"));
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_id=?",Integer.class,e.id()));
    }
    @Test void audioInterpretationUsesServerValidatedCommands() {
        when(gemini.interpret(anyString(),isNull(),anyString())).thenReturn(Json.read("{\"action\":\"CREATE\",\"transcript\":\"치과 예약\",\"event\":{"+"\"title\":\"치과\",\"start\":\"2026-09-25T15:00:00+09:00\",\"allDay\":false}}"));
        var r=Json.read(Json.write(commands.interpret(user,UUID.randomUUID().toString(),"내일 오후 3시 치과",null,null)));
        assertEquals("DONE",Json.str(r,"status"));assertEquals(1,store.events(user).size());
    }
    @Test void tokensAreEncryptedWithDifferentNoncesAndTamperIsRejected() {
        String a=crypto.encrypt("secret-refresh-token"), b=crypto.encrypt("secret-refresh-token");
        assertNotEquals(a,b);assertFalse(a.contains("secret"));assertEquals("secret-refresh-token",crypto.decrypt(a));
        assertThrows(ApiException.class,()->crypto.decrypt("invalid"));
    }
}
