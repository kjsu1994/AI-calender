package kr.dayvoice;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Events {
    static final ZoneId ZONE=ZoneId.of("Asia/Seoul");
    final Store store;
    Events(Store store) {this.store=store;}
    static ObjectNode validate(JsonNode input) {
        String title=Json.str(input,"title").trim(), start=Json.str(input,"start"), end=Json.str(input,"end");
        boolean allDay=input.path("allDay").asBoolean();
        if(title.isBlank() || title.length()>200) throw new ApiException(400,"일정 제목을 200자 이내로 입력해 주세요.");
        try {
            if(allDay) {
                LocalDate a=LocalDate.parse(start), b=end.isBlank()?a.plusDays(1):LocalDate.parse(end);
                if(!b.isAfter(a)) throw new IllegalArgumentException(); end=b.toString();
            } else {
                OffsetDateTime a=OffsetDateTime.parse(start), b=end.isBlank()?a.plusHours(1):OffsetDateTime.parse(end);
                if(!b.isAfter(a)) throw new IllegalArgumentException(); start=a.atZoneSameInstant(ZONE).toOffsetDateTime().toString();end=b.atZoneSameInstant(ZONE).toOffsetDateTime().toString();
            }
        } catch(Exception e) {throw new ApiException(400,"시작·종료 시간을 확인해 주세요. 종료는 시작 이후여야 합니다.");}
        String description=Json.str(input,"description"),location=Json.str(input,"location");
        if(description.length()>5000 || location.length()>500) throw new ApiException(400,"설명 또는 장소가 너무 깁니다.");
        return Json.object().put("title",title).put("start",start).put("end",end).put("allDay",allDay).put("description",description).put("location",location);
    }
    static Instant start(JsonNode d) {return instant(d,"start");}
    static Instant instant(JsonNode d,String key) {return d.path("allDay").asBoolean()?LocalDate.parse(Json.str(d,key)).atStartOfDay(ZONE).toInstant():OffsetDateTime.parse(Json.str(d,key)).toInstant();}
    List<Map<String,Object>> list(String user, LocalDate from, LocalDate to) {
        if(!to.isAfter(from) || from.plusYears(1).isBefore(to)) throw new ApiException(400,"조회 기간은 1년 이내로 지정해 주세요.");
        Instant a=from.atStartOfDay(ZONE).toInstant(),b=to.atStartOfDay(ZONE).toInstant();
        return store.events(user).stream().filter(e -> (!e.deleted() || e.syncState().equals("CONFLICT")) && start(e.data()).isBefore(b) && instant(e.data(),"end").isAfter(a))
            .sorted(Comparator.comparing(e->start(e.data()))).map(Store.Event::view).toList();
    }
    // Callers hold the user's row lock inside a transaction (including the outbox write).
    Store.Event create(String user, JsonNode input) {
        var data=validate(input); Store.User u=store.user(user);
        if(u.calendarId()==null) throw new ApiException(409,"먼저 캘린더를 선택해 주세요.");
        String id=UUID.randomUUID().toString(), googleId=id.replace("-","");
        store.db.update("INSERT INTO calendar_event(id,user_id,google_id,payload,sync_state) VALUES(?,?,?,?,?)",id,user,googleId,Json.write(data),u.demo()?"LOCAL":"PENDING");
        if(!u.demo()) queue(id,"CREATE"); return store.event(user,id);
    }
    Store.Event change(String user,String id,long version,JsonNode input,boolean delete) {
        Store.Event e=store.event(user,id);
        if(e.recurring()) throw new ApiException(400,"반복 일정은 Google Calendar에서 변경해 주세요.");
        if(e.deleted() || e.version()!=version || e.syncState().equals("CONFLICT")) throw new ApiException(409,"일정이 변경되었습니다. 새로고침 후 다시 확인해 주세요.");
        boolean demo=store.user(user).demo(); var data=delete?e.data():validate(input);
        store.db.update("UPDATE calendar_event SET payload=?,deleted=?,version=version+1,sync_state=? WHERE id=?",Json.write(data),delete,demo?"LOCAL":"PENDING",id);
        if(!demo) {
            // Even an unacknowledged create may have reached Google before a timeout.
            queue(id,delete?"DELETE":e.etag()==null?"CREATE":"UPDATE");
        }
        return store.event(user,id);
    }
    void queue(String id,String operation) {
        store.db.update("DELETE FROM outbox WHERE event_id=?",id);
        store.db.update("INSERT INTO outbox(event_id,operation) VALUES(?,?)",id,operation);
    }
    @Transactional
    public Map<String,Object> resolve(String user,String id,String choice,long version) {
        store.lock(user); var e=store.event(user,id);
        if(!e.syncState().equals("CONFLICT") || e.remote()==null || e.version()!=version) throw new ApiException(409,"충돌 상태가 변경되었습니다. 새로고침해 주세요.");
        boolean remoteDeleted=e.remote().path("deleted").asBoolean();
        if(choice.equals("remote")) {
            store.db.update("UPDATE calendar_event SET payload=?,deleted=?,sync_state='SYNCED',remote_payload=NULL,version=version+1 WHERE id=?",
                Json.write(remoteDeleted?e.data():e.remote()),remoteDeleted,id);
            store.db.update("DELETE FROM outbox WHERE event_id=?",id);
        } else if(choice.equals("local")) {
            if(remoteDeleted && !e.deleted()) {
                store.db.update("UPDATE calendar_event SET google_id=?,etag=NULL WHERE id=?",UUID.randomUUID().toString().replace("-",""),id);
            }
            store.db.update("UPDATE calendar_event SET sync_state=?,remote_payload=NULL,version=version+1 WHERE id=?",remoteDeleted && e.deleted()?"SYNCED":"PENDING",id);
            if(remoteDeleted && e.deleted()) store.db.update("DELETE FROM outbox WHERE event_id=?",id);
            else queue(id,e.deleted()?"DELETE":remoteDeleted?"CREATE":"UPDATE");
        } else throw new ApiException(400,"충돌 해결 방식을 선택해 주세요.");
        return store.event(user,id).view();
    }
}
