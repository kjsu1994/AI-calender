package kr.dayvoice;

import java.util.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class SyncBehaviorTests {
    @Autowired Store store; @Autowired Events events; @Autowired SyncService sync;
    @MockitoBean GoogleCalendar google;
    String user;
    @BeforeEach void setup() {
        user=UUID.randomUUID().toString();
        store.db.update("INSERT INTO app_user(id,email,display_name,calendar_id,refresh_token) VALUES(?,?,?,?,?)",user,"test@example.com","Test","cal","encrypted");
        when(google.token(any())).thenReturn("access");when(google.path(any())).thenReturn("calendars/cal/events");
        when(google.call(anyString(),eq("GET"),anyString(),isNull(),isNull())).thenReturn(new HttpGateway.Reply(200,Json.read("{\"items\":[],\"nextSyncToken\":\"cursor\"}")));
    }
    Store.Event create() {return events.create(user,Json.read("{\"title\":\"예약\",\"start\":\"2026-09-25T15:00:00+09:00\",\"end\":\"2026-09-25T16:00:00+09:00\",\"allDay\":false}"));}
    @Test void successfulPushUsesDeterministicGoogleIdAndClearsOutbox() {
        var event=create();
        when(google.call(anyString(),eq("POST"),anyString(),any(),isNull())).thenAnswer(call->{
            var body=(com.fasterxml.jackson.databind.JsonNode)call.getArgument(3);assertEquals(event.googleId(),Json.str(body,"id"));
            return new HttpGateway.Reply(200,Json.read("{\"etag\":\"etag1\"}"));
        });
        sync.sync(user,true);
        assertEquals("SYNCED",store.event(user,event.id()).syncState());
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_id=?",Integer.class,event.id()));
    }
    @Test void networkFailurePersistsRetryInsteadOfReportingSynced() {
        var event=create();
        when(google.call(anyString(),eq("POST"),anyString(),any(),isNull())).thenThrow(new ApiException(503,"network"));
        sync.sync(user,true);
        assertEquals("ERROR",store.event(user,event.id()).syncState());
        assertEquals(1,store.db.queryForObject("SELECT attempts FROM outbox WHERE event_id=?",Integer.class,event.id()));
        assertEquals("ATTENTION",store.user(user).syncStatus());
    }
    @Test void previouslySuccessfulCreateIsAcknowledgedOnPullWithoutDuplicatePost() {
        var event=create();
        String remote="{\"items\":[{\"id\":\""+event.googleId()+"\",\"etag\":\"etag1\",\"summary\":\"예약\",\"start\":{\"dateTime\":\"2026-09-25T06:00:00Z\"},\"end\":{\"dateTime\":\"2026-09-25T07:00:00Z\"}}],\"nextSyncToken\":\"cursor\"}";
        when(google.call(anyString(),eq("GET"),contains("showDeleted"),isNull(),isNull())).thenReturn(new HttpGateway.Reply(200,Json.read(remote)));
        sync.sync(user,true);
        assertEquals("SYNCED",store.event(user,event.id()).syncState());
        verify(google,never()).call(anyString(),eq("POST"),anyString(),any(),any());
    }
    @Test void invalidSyncTokenTriggersFullPull() {
        store.db.update("UPDATE app_user SET sync_token='expired' WHERE id=?",user);
        when(google.call(anyString(),eq("GET"),contains("syncToken=expired"),isNull(),isNull())).thenReturn(new HttpGateway.Reply(410,Json.object()));
        sync.sync(user,true);
        assertEquals("cursor",store.user(user).syncToken());
        verify(google).call(anyString(),eq("GET"),eq("calendars/cal/events?maxResults=250&showDeleted=true"),isNull(),isNull());
    }
    @Test void fullSyncImportsEventsWithRfc3339RecurringQueryOnEveryPage() {
        var bounds = new ArrayList<Map<String,String>>();
        String regular = "{\"items\":[{\"id\":\"regular\",\"etag\":\"etag1\",\"summary\":\"One-off\",\"start\":{\"dateTime\":\"2026-09-25T15:00:00+09:00\"},\"end\":{\"dateTime\":\"2026-09-25T16:00:00+09:00\"}}],\"nextSyncToken\":\"cursor\"}";
        when(google.call(anyString(),eq("GET"),contains("showDeleted"),isNull(),isNull()))
            .thenReturn(new HttpGateway.Reply(200,Json.read(regular)));
        when(google.call(anyString(),eq("GET"),contains("singleEvents=true"),isNull(),isNull())).thenAnswer(call -> {
            Map<String,String> query = queryParameters(call.getArgument(2));
            bounds.add(query);
            // The real provider rejects timestamps missing seconds, even though Java can parse them.
            if (!isRfc3339(query.get("timeMin")) || !isRfc3339(query.get("timeMax")))
                return new HttpGateway.Reply(400,Json.read("{\"error\":{\"errors\":[{\"reason\":\"badRequest\"}]}}"));
            String body = query.containsKey("pageToken") ? "{\"items\":[]}" :
                "{\"items\":[{\"id\":\"instance\",\"recurringEventId\":\"series\",\"etag\":\"etag2\",\"summary\":\"Recurring\",\"start\":{\"dateTime\":\"2026-09-26T15:00:00+09:00\"},\"end\":{\"dateTime\":\"2026-09-26T16:00:00+09:00\"}}],\"nextPageToken\":\"next-page\"}";
            return new HttpGateway.Reply(200,Json.read(body));
        });

        assertDoesNotThrow(() -> sync.sync(user,true));

        assertEquals(2,bounds.size());
        for (var query : bounds) {
            assertTrue(isRfc3339(query.get("timeMin")),query.get("timeMin"));
            assertTrue(isRfc3339(query.get("timeMax")),query.get("timeMax"));
            var min = OffsetDateTime.parse(query.get("timeMin"));
            var max = OffsetDateTime.parse(query.get("timeMax"));
            assertEquals(LocalTime.MIDNIGHT,min.toLocalTime());
            assertEquals(LocalTime.MIDNIGHT,max.toLocalTime());
            assertEquals(ZoneOffset.ofHours(9),min.getOffset());
            assertEquals(ZoneOffset.ofHours(9),max.getOffset());
            assertEquals(396,java.time.temporal.ChronoUnit.DAYS.between(min,max));
            assertTrue(min.isBefore(max));
        }
        assertEquals(bounds.getFirst().get("timeMin"),bounds.getLast().get("timeMin"));
        assertEquals(bounds.getFirst().get("timeMax"),bounds.getLast().get("timeMax"));
        assertEquals("next-page",bounds.getLast().get("pageToken"));
        assertEquals("SYNCED",store.user(user).syncStatus());
        assertEquals("cursor",store.user(user).syncToken());
        assertEquals(2,store.events(user).size());
        assertEquals(1,store.events(user).stream().filter(Store.Event::recurring).count());
    }
    @Test void permissionFailureSurfacesGuidanceWithoutProviderPayload() {
        when(google.call(anyString(),eq("GET"),contains("showDeleted"),isNull(),isNull()))
            .thenReturn(new HttpGateway.Reply(403,Json.read("{\"error\":{\"message\":\"private-provider-detail\",\"errors\":[{\"reason\":\"forbidden\"}]}}")));

        var failure = assertThrows(ApiException.class,() -> sync.sync(user,true));

        assertEquals(503,failure.status);
        assertTrue(failure.getMessage().contains("Calendar API"));
        assertFalse(failure.getMessage().contains("private-provider-detail"));
        assertEquals(failure.getMessage(),sync.error(user));
        assertEquals("ERROR",store.user(user).syncStatus());
    }
    private static Map<String,String> queryParameters(String path) {
        var query = new HashMap<String,String>();
        for (String field : path.substring(path.indexOf('?')+1).split("&")) {
            String[] pair = field.split("=",2);
            query.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8));
        }
        return query;
    }
    private static boolean isRfc3339(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})")) return false;
        try { OffsetDateTime.parse(value); return true; }
        catch (java.time.format.DateTimeParseException ignored) { return false; }
    }
}
