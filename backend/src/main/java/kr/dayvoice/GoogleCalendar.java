package kr.dayvoice;

import java.util.*;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static kr.dayvoice.HttpGateway.enc;

@Service
class GoogleCalendar {
    private final HttpGateway http; private final Crypto crypto; private final AppConfig.Settings cfg;
    GoogleCalendar(HttpGateway http,Crypto crypto,AppConfig.Settings cfg) {this.http=http;this.crypto=crypto;this.cfg=cfg;}
    String token(Store.User user) {
        if(user.demo() || user.refreshToken()==null) throw new ApiException(401,"Google 재연동이 필요합니다.");
        var r=http.form("https://oauth2.googleapis.com/token",Map.of("client_id",cfg.googleClientId(),"client_secret",cfg.googleClientSecret(),"refresh_token",crypto.decrypt(user.refreshToken()),"grant_type","refresh_token"));
        if(r.status()!=200) throw new ApiException(r.status()==400||r.status()==401?401:503,"Google 계정을 다시 연결하거나 잠시 후 재시도해 주세요.");
        return Json.str(r.body(),"access_token");
    }
    HttpGateway.Reply call(String token,String method,String path,JsonNode body,String etag) {
        Map<String,String> headers=new HashMap<>(Map.of("Authorization","Bearer "+token,"Content-Type","application/json"));
        if(etag!=null) headers.put("If-Match",etag);
        return http.send(method,"https://www.googleapis.com/calendar/v3/"+path,body==null?null:Json.write(body),headers);
    }
    String path(Store.User u) {return "calendars/"+enc(u.calendarId())+"/events";}
    List<Map<String,String>> calendars(String user,Store store) {
        String token=token(store.user(user)),page=""; List<Map<String,String>> result=new ArrayList<>();
        do {
            var r=call(token,"GET","users/me/calendarList?minAccessRole=writer&maxResults=250"+(page.isEmpty()?"":"&pageToken="+enc(page)),null,null);
            if(r.status()!=200) throw new ApiException(503,"캘린더 목록을 가져오지 못했습니다.");
            for(var c:r.body().path("items")) result.add(Map.of("id",Json.str(c,"id"),"name",Json.str(c,"summary")));
            page=Json.str(r.body(),"nextPageToken");
        } while(!page.isEmpty());
        return result;
    }
    static ObjectNode fromGoogle(JsonNode g) {
        boolean allDay=g.path("start").has("date");
        return Json.object().put("title",Json.str(g,"summary").isBlank()?"(제목 없음)":Json.str(g,"summary"))
            .put("start",time(g.path("start"),allDay))
            .put("end",time(g.path("end"),allDay))
            .put("allDay",allDay).put("description",Json.str(g,"description")).put("location",Json.str(g,"location"));
    }
    private static String time(JsonNode time, boolean allDay) {
        return allDay ? Json.str(time,"date") : java.time.OffsetDateTime.parse(Json.str(time,"dateTime")).atZoneSameInstant(Events.ZONE).toOffsetDateTime().toString();
    }
    static ObjectNode toGoogle(Store.Event e,boolean insert) {
        var d=e.data();var g=Json.object().put("summary",Json.str(d,"title")).put("description",Json.str(d,"description")).put("location",Json.str(d,"location"));
        if(insert) g.put("id",e.googleId());
        for(String k:List.of("start","end")) {
            boolean allDay=d.path("allDay").asBoolean();
            String value=allDay?Json.str(d,k):java.time.OffsetDateTime.parse(Json.str(d,k)).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            var time=Json.object().put(allDay?"date":"dateTime",value);
            if(!d.path("allDay").asBoolean()) time.put("timeZone","Asia/Seoul");
            g.set(k,time);
        }
        return g;
    }
}
