package kr.dayvoice;

import java.time.LocalDate;
import java.util.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

@RestController
@RequestMapping("/api")
class ApiController {
    private final Store store;private final Events events;private final Commands commands;private final GoogleCalendar google;private final SyncService sync;private final TransactionTemplate tx;private final ActivityRecords records;
    ApiController(Store store,Events events,Commands commands,GoogleCalendar google,SyncService sync,TransactionTemplate tx,ActivityRecords records) {
        this.store=store;this.events=events;this.commands=commands;this.google=google;this.sync=sync;this.tx=tx;this.records=records;
    }
    @GetMapping("/calendars") Object calendars(HttpSession s) {
        String user=AuthController.uid(s);if(store.user(user).demo())return List.of(Map.of("id","demo","name","샘플 캘린더"));
        return google.calendars(user,store);
    }
    record Selection(String calendarId) {}
    @PostMapping("/calendars/select") Object select(HttpSession s,@RequestBody Selection body) {
        String user=AuthController.uid(s);if(store.user(user).demo())throw new ApiException(400,"샘플 캘린더는 변경할 수 없습니다.");
        var selected=google.calendars(user,store).stream().filter(c->c.get("id").equals(body.calendarId())).findFirst().orElseThrow(()->new ApiException(400,"쓰기 가능한 캘린더를 선택해 주세요."));
        tx.executeWithoutResult(t->{store.lock(user);
            var u=store.user(user);
            if(u.calendarId()!=null && !u.calendarId().equals(body.calendarId()))throw new ApiException(409,"첫 버전은 연결 후 캘린더 전환을 지원하지 않습니다.");
            store.db.update("UPDATE app_user SET calendar_id=?,calendar_name=?,sync_status='PENDING' WHERE id=?",body.calendarId(),selected.get("name"),user);
        });return Map.of("ok",true);
    }
    @GetMapping("/events") Object list(HttpSession s,@RequestParam String from,@RequestParam String to) {return events.list(AuthController.uid(s),LocalDate.parse(from),LocalDate.parse(to));}
    @GetMapping("/records") Object records(HttpSession s,@RequestParam String from,@RequestParam String to) {return records.list(AuthController.uid(s),LocalDate.parse(from),LocalDate.parse(to));}
    record RecordInput(String requestId,long version,Map<String,Object> record) {}
    @PostMapping("/records") Object createRecord(HttpSession s,@RequestBody RecordInput body) {return commands.manualRecord(AuthController.uid(s),body.requestId(),"LOG_CREATE","",0,Json.read(Json.write(body.record())));}
    @PutMapping("/records/{id}") Object updateRecord(HttpSession s,@PathVariable String id,@RequestBody RecordInput body) {return commands.manualRecord(AuthController.uid(s),body.requestId(),"LOG_UPDATE",id,body.version(),Json.read(Json.write(body.record())));}
    @DeleteMapping("/records/{id}") Object deleteRecord(HttpSession s,@PathVariable String id,@RequestBody RecordInput body) {return commands.manualRecord(AuthController.uid(s),body.requestId(),"LOG_DELETE",id,body.version(),Json.object());}
    record Manual(String requestId,long version,Map<String,Object> event) {}
    @PostMapping("/events") Object create(HttpSession s,@RequestBody Manual body) {return commands.manual(AuthController.uid(s),body.requestId(),"CREATE","",0,Json.read(Json.write(body.event())));}
    @PutMapping("/events/{id}") Object update(HttpSession s,@PathVariable String id,@RequestBody Manual body) {return commands.manual(AuthController.uid(s),body.requestId(),"UPDATE",id,body.version(),Json.read(Json.write(body.event())));}
    @DeleteMapping("/events/{id}") Object delete(HttpSession s,@PathVariable String id,@RequestBody Manual body) {return commands.manual(AuthController.uid(s),body.requestId(),"DELETE",id,body.version(),Json.object());}
    record TextInput(String requestId,String text,String parentId) {}
    @PostMapping("/commands/text") Object text(HttpSession s,@RequestBody TextInput body) {return commands.interpret(AuthController.uid(s),body.requestId(),Objects.toString(body.text(),""),null,body.parentId());}
    @PostMapping("/commands/audio") Object audio(HttpSession s,@RequestParam String requestId,@RequestParam(required=false) String parentId,@RequestPart MultipartFile audio) throws Exception {
        return commands.interpret(AuthController.uid(s),requestId,"",audio.getBytes(),parentId);
    }
    record Confirmation(boolean confirm) {}
    @PostMapping("/commands/{id}/confirm") Object confirm(HttpSession s,@PathVariable String id,@RequestBody Confirmation body) {return commands.finish(AuthController.uid(s),id,body.confirm());}
    record Resolution(String choice,long version) {}
    @PostMapping("/events/{id}/resolve") Object resolve(HttpSession s,@PathVariable String id,@RequestBody Resolution body) {return events.resolve(AuthController.uid(s),id,body.choice(),body.version());}
    @GetMapping("/sync") Object status(HttpSession s) {
        var u=store.user(AuthController.uid(s));return Map.of("status",u.syncStatus(),"lastSync",Objects.toString(u.lastSync(),""),"issues",store.events(u.id()).stream().filter(e->List.of("PENDING","ERROR","CONFLICT").contains(e.syncState())).map(Store.Event::view).toList());
    }
    @PostMapping("/sync") Object sync(HttpSession s) {sync.sync(AuthController.uid(s),true);return status(s);}
}
