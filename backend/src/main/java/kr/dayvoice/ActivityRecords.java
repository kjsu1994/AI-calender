package kr.dayvoice;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
class ActivityRecords {
    private final Store store;
    ActivityRecords(Store store) { this.store=store; }
    record Activity(String id,String eventId,String eventTitle,JsonNode data,long version,String createdAt,String updatedAt) {
        Map<String,Object> view() {
            var result=new LinkedHashMap<String,Object>();
            result.put("id",id);result.put("eventId",eventId);result.put("eventTitle",eventTitle);
            result.put("data",Json.plain(data));result.put("version",version);
            result.put("createdAt",createdAt);result.put("updatedAt",updatedAt);
            return result;
        }
    }
    List<Activity> all(String user) {
        return store.db.query("SELECT * FROM activity_record WHERE user_id=?",(r,n)->new Activity(r.getString("id"),r.getString("event_id"),r.getString("event_title"),
            Json.read(r.getString("payload")),r.getLong("version"),r.getString("created_at"),r.getString("updated_at")),user);
    }
    Activity find(String user,String id) {
        return all(user).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->new ApiException(404,"기록을 찾을 수 없습니다."));
    }
    List<Map<String,Object>> list(String user,LocalDate from,LocalDate to) {
        if(!to.isAfter(from) || from.plusYears(1).isBefore(to))throw new ApiException(400,"조회 기간은 1년 이내로 지정해 주세요.");
        Instant a=from.atStartOfDay(Events.ZONE).toInstant(),b=to.atStartOfDay(Events.ZONE).toInstant();
        return all(user).stream().filter(r->Events.start(r.data()).isBefore(b) && Events.instant(r.data(),"end").isAfter(a))
            .sorted(Comparator.comparing(r->Events.start(r.data()))).map(Activity::view).toList();
    }
    ObjectNode validate(String user,JsonNode input) {
        if(Json.str(input,"start").isBlank() || Json.str(input,"end").isBlank())throw new ApiException(400,"기록의 시작 시간과 종료 시간을 모두 알려 주세요.");
        if(Json.str(input,"content").isBlank())throw new ApiException(400,"기록할 내용을 입력해 주세요.");
        var data=Events.validate(Json.object().put("title",Json.str(input,"title")).put("start",Json.str(input,"start"))
            .put("end",Json.str(input,"end")).put("allDay",false).put("description",Json.str(input,"content")));
        String eventId=Json.str(input,"eventId");
        if(!eventId.isBlank()) {
            // A completed activity remains editable even if its original schedule was deleted.
            store.event(user,eventId);
        }
        return Json.object().put("title",Json.str(data,"title")).put("start",Json.str(data,"start"))
            .put("end",Json.str(data,"end")).put("content",Json.str(data,"description")).put("eventId",eventId);
    }
    Activity create(String user,JsonNode input) {
        store.user(user);
        var normalized=validate(user,input);String id=UUID.randomUUID().toString(),now=Instant.now().toString();
        String eventId=Json.str(normalized,"eventId"),eventTitle=eventId.isBlank()?null:Json.str(store.event(user,eventId).data(),"title");
        store.db.update("INSERT INTO activity_record(id,user_id,event_id,event_title,payload,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",id,user,eventId.isBlank()?null:eventId,eventTitle,Json.write(data(normalized)),now,now);
        return find(user,id);
    }
    Activity change(String user,String id,long version,JsonNode input,boolean delete) {
        var existing=find(user,id);
        if(existing.version()!=version)throw new ApiException(409,"기록이 변경되었습니다. 다시 확인해 주세요.");
        if(delete) {
            store.db.update("DELETE FROM activity_record WHERE id=? AND user_id=?",id,user);
            return existing;
        }
        var normalized=validate(user,input);
        String eventId=Json.str(normalized,"eventId"),eventTitle=eventId.isBlank()?null:Json.str(store.event(user,eventId).data(),"title");
        store.db.update("UPDATE activity_record SET event_id=?,event_title=?,payload=?,version=version+1,updated_at=? WHERE id=? AND user_id=?",eventId.isBlank()?null:eventId,eventTitle,Json.write(data(normalized)),Instant.now().toString(),id,user);
        return find(user,id);
    }
    ObjectNode data(JsonNode normalized) {
        return Json.object().put("title",Json.str(normalized,"title")).put("start",Json.str(normalized,"start"))
            .put("end",Json.str(normalized,"end")).put("allDay",false).put("description",Json.str(normalized,"content")).put("location","");
    }
}
