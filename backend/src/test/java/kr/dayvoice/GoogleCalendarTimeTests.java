package kr.dayvoice;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class GoogleCalendarTimeTests {
    private Store.Event event(String start, String end, boolean allDay) {
        ObjectNode data = Json.object().put("title", "Test").put("start", start).put("end", end)
            .put("allDay", allDay).put("description", "").put("location", "");
        return new Store.Event("event", "user", "google-event", null, data, null, "PENDING", 1, false, false);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-09-25T15:00+09:00,2026-09-25T16:00+09:00,2026-09-25T15:00:00+09:00,2026-09-25T16:00:00+09:00",
        "2026-09-25T06:00Z,2026-09-25T07:00Z,2026-09-25T06:00:00Z,2026-09-25T07:00:00Z"
    })
    void outboundTimesIncludeSecondsWithoutChangingInternalPayload(String start, String end, String expectedStart, String expectedEnd) {
        var event = event(start, end, false);
        var original = event.data().deepCopy();

        var google = GoogleCalendar.toGoogle(event, true);

        assertEquals(expectedStart, Json.str(google.path("start"), "dateTime"));
        assertEquals(expectedEnd, Json.str(google.path("end"), "dateTime"));
        assertEquals(original, event.data());
    }

    @Test void outboundTimesPreserveFractionalSecondsOffsetAndInstant() {
        String start = "2026-09-25T15:00:12.123456789+05:30";
        String end = "2026-09-25T16:00:13.987654321+05:30";

        var google = GoogleCalendar.toGoogle(event(start, end, false), false);

        assertEquals(start, Json.str(google.path("start"), "dateTime"));
        assertEquals(end, Json.str(google.path("end"), "dateTime"));
        assertEquals(OffsetDateTime.parse(start).toInstant(), OffsetDateTime.parse(Json.str(google.path("start"), "dateTime")).toInstant());
        assertEquals(OffsetDateTime.parse(end).toInstant(), OffsetDateTime.parse(Json.str(google.path("end"), "dateTime")).toInstant());
    }

    @Test void allDayDatesRemainDateOnlyWithExclusiveEnd() {
        var google = GoogleCalendar.toGoogle(event("2026-09-25", "2026-09-27", true), true);

        assertEquals(Json.read("{\"date\":\"2026-09-25\"}"), google.path("start"));
        assertEquals(Json.read("{\"date\":\"2026-09-27\"}"), google.path("end"));
    }

    @Test void roundTripRetainsCanonicalInternalTimesForSyncEquality() {
        var event = event("2026-09-25T15:00+09:00", "2026-09-25T16:00+09:00", false);

        var roundTrip = GoogleCalendar.fromGoogle(GoogleCalendar.toGoogle(event, true));

        assertEquals(event.data(), roundTrip);
    }
}
