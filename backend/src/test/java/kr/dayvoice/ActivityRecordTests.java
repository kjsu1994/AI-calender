package kr.dayvoice;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class ActivityRecordTests {
    @Autowired Store store; @Autowired ActivityRecords records; @Autowired Commands commands; @Autowired Events events;
    @MockitoBean Gemini gemini;
    String user;
    @BeforeEach void setup() {
        user=UUID.randomUUID().toString();
        store.db.update("INSERT INTO app_user(id,email,display_name,demo) VALUES(?,?,?,TRUE)",user,"records@example.com","기록 사용자");
    }
    com.fasterxml.jackson.databind.node.ObjectNode input(String content) {
        return Json.object().put("title","고객 미팅").put("start","2026-09-25T12:00:00+09:00").put("end","2026-09-25T13:00:00+09:00")
            .put("content",content).put("eventId","");
    }
    @Test void independentActivityDoesNotCreateCalendarEventOrOutboxAndIsIdempotent() {
        String request=UUID.randomUUID().toString();
        Object a=commands.manualRecord(user,request,"LOG_CREATE","",0,input("견적 수정 요청받음"));
        Object b=commands.manualRecord(user,request,"LOG_CREATE","",0,input("견적 수정 요청받음"));
        assertEquals(Json.read(Json.write(a)),Json.read(Json.write(b)));assertEquals(1,records.all(user).size());
        assertEquals(0,store.events(user).size());
        assertNull(records.all(user).getFirst().eventId());
    }
    @Test void recordRequiresExplicitEndAndContentAndValidInterval() {
        assertThrows(ApiException.class,()->records.create(user,input("내용").put("end","")));
        assertThrows(ApiException.class,()->records.create(user,input("내용").put("end","2026-09-25T11:00:00+09:00")));
        assertThrows(ApiException.class,()->records.create(user,input("")));
    }
    @Test void activityEditsNeedConfirmationAndVersionsPreventLostUpdates() {
        var record=records.create(user,input("원래 내용"));
        var preview=Json.read(Json.write(commands.manualRecord(user,UUID.randomUUID().toString(),"LOG_UPDATE",record.id(),1,input("수정 내용"))));
        assertEquals("원래 내용",Json.str(records.find(user,record.id()).data(),"description"));
        commands.finish(user,Json.str(preview,"commandId"),true);
        assertEquals("수정 내용",Json.str(records.find(user,record.id()).data(),"description"));
        var stale=Json.read(Json.write(commands.manualRecord(user,UUID.randomUUID().toString(),"LOG_DELETE",record.id(),2,Json.object())));
        records.change(user,record.id(),2,input("동시 수정"),false);
        assertThrows(ApiException.class,()->commands.finish(user,Json.str(stale,"commandId"),true));
    }
    @Test void deletionCanBeCancelledAndConfirmedOnlyOnce() {
        var record=records.create(user,input("삭제 확인"));
        var cancelled=Json.read(Json.write(commands.manualRecord(user,UUID.randomUUID().toString(),"LOG_DELETE",record.id(),1,Json.object())));
        commands.finish(user,Json.str(cancelled,"commandId"),false);assertEquals(1,records.all(user).size());
        var confirmed=Json.read(Json.write(commands.manualRecord(user,UUID.randomUUID().toString(),"LOG_DELETE",record.id(),1,Json.object())));
        commands.finish(user,Json.str(confirmed,"commandId"),true);commands.finish(user,Json.str(confirmed,"commandId"),true);
        assertEquals(0,records.all(user).size());
    }
    @Test void linkedRecordsSurviveScheduleRemovalAndCannotCrossUsers() {
        store.db.update("UPDATE app_user SET calendar_id='demo' WHERE id=?",user);
        var event=events.create(user,Json.object().put("title","고객 미팅 예정").put("start","2026-09-25T12:00:00+09:00").put("allDay",false));
        var record=records.create(user,input("견적 논의").put("eventId",event.id()));
        assertThrows(ApiException.class,()->records.find("another-user",record.id()));
        assertThrows(ApiException.class,()->records.validate("another-user",input("외부 접근").put("eventId",event.id())));
        events.change(user,event.id(),1,Json.object(),true);
        records.change(user,record.id(),1,input("추가 내용").put("eventId",event.id()),false);
        store.db.update("DELETE FROM calendar_event WHERE id=?",event.id());
        var kept=records.find(user,record.id());
        assertNull(kept.eventId());assertEquals("고객 미팅 예정",kept.eventTitle());
        assertEquals("추가 내용",Json.str(kept.data(),"description"));
    }
    @Test void recordsCrossingMidnightAppearOnBothDays() {
        records.create(user,input("야간 작업").put("start","2026-09-25T23:30:00+09:00").put("end","2026-09-26T00:30:00+09:00"));
        assertEquals(1,records.list(user,LocalDate.parse("2026-09-25"),LocalDate.parse("2026-09-26")).size());
        assertEquals(1,records.list(user,LocalDate.parse("2026-09-26"),LocalDate.parse("2026-09-27")).size());
        assertEquals(0,records.list(user,LocalDate.parse("2026-09-27"),LocalDate.parse("2026-09-28")).size());
    }
    @Test void voiceRecordCreatesActivityAndQueriesContentWithoutCreatingEvent() {
        when(gemini.interpret(anyString(),isNull(),anyString())).thenReturn(Json.object().put("action","LOG_CREATE").put("transcript","12시부터 13시까지 미팅").set("record",input("견적 수정 요청받음")));
        var created=Json.read(Json.write(commands.interpret(user,UUID.randomUUID().toString(),"활동 기록",null,null)));
        assertTrue(created.has("record"));assertEquals(0,store.events(user).size());
        when(gemini.interpret(anyString(),isNull(),anyString())).thenReturn(Json.object().put("action","LOG_READ").put("queryFrom","2026-09-25").put("queryTo","2026-09-26"));
        var read=Json.read(Json.write(commands.interpret(user,UUID.randomUUID().toString(),"오늘 기록 보여줘",null,null)));
        assertEquals(1,read.path("records").size());assertTrue(Json.str(read,"message").contains("견적 수정 요청받음"));
    }
}
