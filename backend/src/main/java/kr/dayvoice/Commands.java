package kr.dayvoice;

import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Commands {
    private final Store store;private final Events events;private final Gemini gemini;private final ActivityRecords records;
    Commands(Store store,Events events,Gemini gemini,ActivityRecords records) {this.store=store;this.events=events;this.gemini=gemini;this.records=records;}
    @Transactional
    public Object interpret(String user,String requestId,String text,byte[] audio,String parentId) {
        store.lock(user);
        if(text.length()>4000) throw new ApiException(400,"입력은 4,000자 이내로 작성해 주세요.");
        String fingerprint=hash(text+"|"+Objects.toString(parentId,"")+"|"+(audio==null?"":Base64.getEncoder().encodeToString(audio)));
        Object cached=cached(user,requestId,fingerprint);if(cached!=null)return cached;
        Map<String,Object> prior=parentId==null||parentId.isBlank()?null:command(user,parentId);
        if(prior!=null && (long)prior.get("expires_at")<System.currentTimeMillis()) throw new ApiException(409,"이전 대화가 만료되었습니다. 다시 요청해 주세요.");
        var all=store.events(user).stream().filter(e->!e.deleted()).sorted(Comparator.comparing(e->Events.start(e.data()))).map(Store.Event::view).toList();
        Object previous = prior == null ? Map.of() : Map.of("plan", Json.plain(Json.read(prior.get("plan").toString())),
            "result", Json.plain(Json.read(prior.get("result").toString())));
        String context=Json.write(Map.of("now",ZonedDateTime.now(Events.ZONE).toString(),"events",all,
            "records",records.all(user).stream().map(ActivityRecords.Activity::view).toList(),"previous",previous));
        if(context.length()>150000) throw new ApiException(400,"일정이 너무 많습니다. 직접 입력 화면에서 처리해 주세요.");
        JsonNode parsed=gemini.interpret(text,audio,context);
        if (audio != null && Json.str(parsed,"transcript").isBlank())
            throw new ApiException(400,"음성이 감지되지 않았습니다. 다시 녹음해 주세요.");
        String action=Json.str(parsed,"action");
        ObjectNode plan=parsed.deepCopy();
        if(prior!=null) plan.set("previousPlan",Json.read(prior.get("plan").toString()));
        if(Json.write(plan).length()>100000) throw new ApiException(400,"대화가 길어졌습니다. 새 요청으로 시작해 주세요.");
        if(action.equals("CONFIRM") || action.equals("CANCEL")) {
            if(prior==null || !Json.str(Json.read(prior.get("result").toString()),"status").equals("CONFIRMATION"))
                return save(user,requestId,fingerprint,plan,result("NEEDS_INPUT","확인할 변경 요청이 없습니다. 할 일을 다시 말해 주세요.",plan));
            Object confirmed=finishLocked(user,parentId,action.equals("CONFIRM"));
            return save(user,requestId,fingerprint,plan,(ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(confirmed));
        }
        ObjectNode result;
        switch(action) {
            case "LOG_CREATE" -> {
                var record=records.create(user,parsed.path("record"));
                result=result("DONE","시간별 활동 기록을 저장했습니다.",plan);
                result.set("record",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(record.view()));
            }
            case "LOG_UPDATE", "LOG_DELETE" -> {
                var record=records.find(user,Json.str(parsed,"targetId"));
                plan.put("version",record.version());plan.set("before",record.data());
                if(action.equals("LOG_UPDATE"))plan.set("record",records.validate(user,parsed.path("record")));
                result=recordProposal(plan,record,action.equals("LOG_DELETE"));
            }
            case "LOG_READ" -> {
                LocalDate from=Json.str(parsed,"queryFrom").isBlank()?LocalDate.now(Events.ZONE):LocalDate.parse(Json.str(parsed,"queryFrom"));
                LocalDate to=Json.str(parsed,"queryTo").isBlank()?from.plusDays(1):LocalDate.parse(Json.str(parsed,"queryTo"));
                var items=records.list(user,from,to);
                String summary=items.isEmpty()?"해당 기간에 활동 기록이 없습니다.":items.size()+"개의 활동 기록이 있습니다. "+items.stream().limit(5).map(v->{
                    var d=Json.read(Json.write(v.get("data")));String content=Json.str(d,"description");
                    return Json.str(d,"start")+"부터 "+Json.str(d,"end")+"까지 "+Json.str(d,"title")+". "+content.substring(0,Math.min(200,content.length()));
                }).reduce((a,b)->a+". "+b).orElse("");
                result=result("DONE",summary,plan);result.set("records",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(items));
            }
            case "CREATE" -> {
                var e=events.create(user,parsed.path("event"));
                result=result("DONE","일정을 저장했습니다. "+(store.user(user).demo()?"샘플 일정에 반영했습니다.":"Google 동기화를 기다리고 있습니다."),plan);
                result.set("event",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(e.view()));
            }
            case "UPDATE", "DELETE" -> {
                var e=store.event(user,Json.str(parsed,"targetId"));
                if(e.recurring()) throw new ApiException(400,"반복 일정은 조회만 지원합니다. Google Calendar에서 수정해 주세요.");
                if(e.deleted()) throw new ApiException(409,"이미 삭제된 일정입니다.");
                plan.put("version",e.version());plan.set("before",e.data());
                if(action.equals("UPDATE"))plan.set("event",Events.validate(parsed.path("event")));
                result=result("CONFIRMATION",action.equals("DELETE")?"이 일정을 삭제할까요?":"이 내용으로 일정을 수정할까요?",plan);
                result.set("before",e.data());result.set("after",action.equals("DELETE")?Json.object().put("deleted",true):plan.path("event"));
            }
            case "READ" -> {
                LocalDate from=Json.str(parsed,"queryFrom").isBlank()?LocalDate.now(Events.ZONE):LocalDate.parse(Json.str(parsed,"queryFrom"));
                LocalDate to=Json.str(parsed,"queryTo").isBlank()?from.plusDays(1):LocalDate.parse(Json.str(parsed,"queryTo"));
                var items=events.list(user,from,to);
                String summary=items.isEmpty()?"해당 기간에 일정이 없습니다.":items.size()+"개의 일정이 있습니다. "+items.stream().limit(5).map(v -> {var d=Json.read(Json.write(v.get("data")));return Json.str(d,"title")+" "+Json.str(d,"start");}).reduce((a,b)->a+", "+b).orElse("");
                result=result("DONE",summary,plan);result.set("events",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(items));
            }
            default -> result=result("NEEDS_INPUT",Json.str(parsed,"message").isBlank()?"어떤 일정을 처리할까요?":Json.str(parsed,"message"),plan);
        }
        if(prior!=null) {
            // An abandoned proposal must not remain executable from another tab.
            var old=(ObjectNode)Json.read(prior.get("result").toString());old.put("status","CANCELLED");
            store.db.update("UPDATE voice_command SET result=? WHERE id=? AND user_id=?",Json.write(old),parentId,user);
        }
        return save(user,requestId,fingerprint,plan,result);
    }
    @Transactional
    public Object manualRecord(String user,String requestId,String action,String id,long version,JsonNode input) {
        store.lock(user);
        String fingerprint=hash(action+id+version+Json.write(input));Object cached=cached(user,requestId,fingerprint);if(cached!=null)return cached;
        var plan=Json.object().put("action",action).put("targetId",Objects.toString(id,"")).put("version",version).put("transcript","");
        plan.set("record",input);
        ObjectNode result;
        if(action.equals("LOG_CREATE")) {
            var record=records.create(user,input);result=result("DONE","시간별 활동 기록을 저장했습니다.",plan);
            result.set("record",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(record.view()));
        } else {
            var record=records.find(user,id);
            if(record.version()!=version)throw new ApiException(409,"기록이 변경되었습니다. 다시 확인해 주세요.");
            if(action.equals("LOG_UPDATE"))plan.set("record",records.validate(user,input));
            else if(!action.equals("LOG_DELETE"))throw new ApiException(400,"지원하지 않는 기록 동작입니다.");
            plan.set("before",record.data());result=recordProposal(plan,record,action.equals("LOG_DELETE"));
        }
        return save(user,requestId,fingerprint,plan,result);
    }
    private ObjectNode recordProposal(ObjectNode plan,ActivityRecords.Activity record,boolean delete) {
        var result=result("CONFIRMATION",delete?"이 활동 기록을 삭제할까요?":"이 내용으로 활동 기록을 수정할까요?",plan);
        result.put("kind","record");result.set("before",record.data());
        result.set("after",delete?Json.object().put("deleted",true):records.data(plan.path("record")));
        if(!delete) {
            String linked=Json.str(plan.path("record"),"eventId");
            result.put("linkedEventId",linked);
        }
        return result;
    }
    @Transactional
    public Object manual(String user,String requestId,String action,String id,long version,JsonNode input) {
        store.lock(user);
        String fingerprint=hash(action+id+version+Json.write(input));Object cached=cached(user,requestId,fingerprint);if(cached!=null)return cached;
        var plan=Json.object().put("action",action).put("targetId",id==null?"":id).put("version",version).put("transcript","");
        plan.set("event",input);
        ObjectNode r;
        if(action.equals("CREATE")) {
            var e=events.create(user,input);r=result("DONE","일정을 저장했습니다.",plan);r.set("event",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(e.view()));
        } else {
            var e=store.event(user,id);
            if(e.version()!=version || e.deleted() || e.recurring() || e.syncState().equals("CONFLICT")) throw new ApiException(409,"변경할 수 없는 일정입니다. 새로고침 후 확인해 주세요.");
            plan.set("before",e.data());
            if(action.equals("UPDATE"))plan.set("event",Events.validate(input));
            else if(!action.equals("DELETE"))throw new ApiException(400,"지원하지 않는 동작입니다.");
            r=result("CONFIRMATION",action.equals("DELETE")?"이 일정을 삭제할까요?":"이 내용으로 일정을 수정할까요?",plan);
            r.set("before",e.data());r.set("after",action.equals("DELETE")?Json.object().put("deleted",true):plan.path("event"));
        }
        return save(user,requestId,fingerprint,plan,r);
    }
    @Transactional
    public Object finish(String user,String id,boolean confirm) {store.lock(user);return finishLocked(user,id,confirm);}
    private Object finishLocked(String user,String id,boolean confirm) {
        var row=command(user,id);var r=(ObjectNode)Json.read(row.get("result").toString());
        if(Json.str(r,"status").equals("DONE") || Json.str(r,"status").equals("CANCELLED"))return Json.plain(r);
        if(!Json.str(r,"status").equals("CONFIRMATION") || (long)row.get("expires_at")<System.currentTimeMillis())throw new ApiException(409,"확인 요청이 만료되었거나 유효하지 않습니다.");
        if(confirm) {
            var p=Json.read(row.get("plan").toString());
            if(Json.str(p,"action").startsWith("LOG_")) {
                boolean delete=Json.str(p,"action").equals("LOG_DELETE");
                var record=records.change(user,Json.str(p,"targetId"),p.path("version").asLong(),p.path("record"),delete);
                var view=record.view();view.put("deleted",delete);
                r.set("record",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(view));
            } else {
                var e=events.change(user,Json.str(p,"targetId"),p.path("version").asLong(),p.path("event"),Json.str(p,"action").equals("DELETE"));
                r.set("event",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(e.view()));
            }
            r.put("status","DONE").put("message","요청한 변경을 저장했습니다.");
        } else r.put("status","CANCELLED").put("message","변경을 취소했습니다.");
        store.db.update("UPDATE voice_command SET result=? WHERE id=? AND user_id=?",Json.write(r),id,user);return Json.plain(r);
    }
    private Object cached(String user,String requestId,String fingerprint) {
        if(requestId==null || !requestId.matches("[a-zA-Z0-9-]{8,80}"))throw new ApiException(400,"요청 식별자가 필요합니다.");
        var rows=store.db.queryForList("SELECT fingerprint,result FROM voice_command WHERE user_id=? AND request_id=?",user,requestId);
        if(rows.isEmpty())return null;
        if(!rows.getFirst().get("fingerprint").equals(fingerprint))throw new ApiException(409,"이미 사용된 요청 식별자입니다.");
        return Json.plain(Json.read(rows.getFirst().get("result").toString()));
    }
    private Map<String,Object> command(String user,String id) {
        return store.db.queryForList("SELECT * FROM voice_command WHERE id=? AND user_id=?",id,user).stream().findFirst().orElseThrow(()->new ApiException(404,"이전 요청을 찾을 수 없습니다."));
    }
    private Object save(String user,String requestId,String fingerprint,JsonNode plan,ObjectNode result) {
        String id=UUID.randomUUID().toString();result.put("commandId",id);
        store.db.update("INSERT INTO voice_command(id,user_id,request_id,fingerprint,plan,result,expires_at) VALUES(?,?,?,?,?,?,?)",id,user,requestId,fingerprint,Json.write(plan),Json.write(result),System.currentTimeMillis()+600000);
        return Json.plain(result);
    }
    private ObjectNode result(String status,String message,JsonNode plan) {return Json.object().put("status",status).put("message",message).put("transcript",Json.str(plan,"transcript"));}
    private String hash(String text) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
