package kr.dayvoice;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class GoogleAccountLinkerTests {
    @Autowired Store store;
    @Autowired Crypto crypto;
    @Autowired GoogleAccountLinker linker;

    @Test void existingGoogleAccountKeepsExactOwnerIdAndAll246EventsAndActivityRecords() {
        String subject = UUID.randomUUID().toString(), email = email();
        insertUser(subject, email, subject, crypto.encrypt("old-token"), false);
        store.db.update("UPDATE app_user SET calendar_id='primary',calendar_name='My calendar',"
                + "sync_token='cursor',last_sync='2026-09-25T10:00:00Z',sync_status='ERROR' WHERE id=?", subject);
        String linkedEvent = null;
        for (int index = 0; index < 246; index++) linkedEvent = insertEvent(subject, index);
        String recordId = insertActivity(subject, linkedEvent);
        var beforeEvents = store.events(subject);
        var beforeRecord = record(recordId);

        assertEquals(subject, linker.link(null, subject, email.toUpperCase(), "Google name", "new-token"));

        Store.User user = store.user(subject);
        assertEquals(email, user.email());
        assertEquals("Original name", user.name());
        assertEquals("primary", user.calendarId());
        assertEquals("My calendar", user.calendarName());
        assertEquals("cursor", user.syncToken());
        assertEquals("2026-09-25T10:00:00Z", user.lastSync());
        assertEquals("PENDING", user.syncStatus());
        assertEquals("new-token", crypto.decrypt(user.refreshToken()));
        assertNotEquals("new-token", user.refreshToken());
        assertEquals(beforeEvents, store.events(subject));
        assertEquals(beforeRecord, record(recordId));
        assertEquals(1, usersWithEmail(email));
    }

    @Test void localUuidAccountLinksGoogleWithoutMovingOrDuplicatingAnyData() {
        String localId = UUID.randomUUID().toString(), subject = UUID.randomUUID().toString(), email = email();
        insertUser(localId, email.toUpperCase(), null, null, false);
        String event = insertEvent(localId, 0), activity = insertActivity(localId, event);
        var beforeEvent = store.events(localId);
        var beforeRecord = record(activity);

        assertEquals(localId, linker.link(localId, subject, email, "Google name", "refresh"));

        assertEquals(subject, subject(localId));
        assertEquals("DISCONNECTED", store.user(localId).syncStatus());
        assertEquals("refresh", crypto.decrypt(store.user(localId).refreshToken()));
        assertEquals(beforeEvent, store.events(localId));
        assertEquals(beforeRecord, record(activity));
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=?", Integer.class, subject));
        assertEquals(1, usersWithEmail(email));
    }

    @Test void signedOutGoogleLoginAlsoFindsAnExistingLocalAccountByEmail() {
        String localId = UUID.randomUUID().toString(), subject = UUID.randomUUID().toString(), email = email();
        insertUser(localId, email, null, null, false);
        assertEquals(localId, linker.link(null, subject, email.toUpperCase(), "Google name", "refresh"));
        assertEquals(subject, subject(localId));
        assertEquals(1, usersWithEmail(email));
    }

    @Test void firstGoogleSignInCreatesOneUserAndEncryptsTheRefreshToken() {
        String subject = UUID.randomUUID().toString(), email = email();
        assertEquals(subject, linker.link(null, subject, email.toUpperCase(), "New user", "refresh"));
        Store.User user = store.user(subject);
        assertEquals(email, user.email());
        assertEquals("New user", user.name());
        assertEquals(subject, subject(subject));
        assertEquals("DISCONNECTED", user.syncStatus());
        assertFalse(user.demo());
        assertNotEquals("refresh", user.refreshToken());
        assertEquals("refresh", crypto.decrypt(user.refreshToken()));
    }

    @Test void missingProviderRefreshTokenKeepsExistingCiphertextAndReconnectsCalendar() {
        String id = UUID.randomUUID().toString(), email = email();
        String encrypted = crypto.encrypt("saved-token");
        insertUser(id, email, id, encrypted, false);
        store.db.update("UPDATE app_user SET calendar_id='primary',sync_status='ERROR' WHERE id=?", id);

        assertEquals(id, linker.link(id, id, email, "Name", ""));
        assertEquals(encrypted, store.user(id).refreshToken());
        assertEquals("PENDING", store.user(id).syncStatus());
        assertEquals(id, linker.link(null, id, email, "Name", null));
        assertEquals(encrypted, store.user(id).refreshToken());
    }

    @Test void noRefreshTokenRejectsNewAndUnlinkedAccountsWithoutPartialWrites() {
        String newSubject = UUID.randomUUID().toString(), newEmail = email();
        rejects(400, () -> linker.link(null, newSubject, newEmail, "Name", ""));
        assertEquals(0, usersWithEmail(newEmail));

        String localId = UUID.randomUUID().toString(), localEmail = email();
        insertUser(localId, localEmail, null, null, false);
        rejects(400, () -> linker.link(localId, newSubject, localEmail, "Name", null));
        assertNull(subject(localId));
        assertNull(store.user(localId).refreshToken());
    }

    @Test void differentCurrentAccountCannotSwitchToOrLinkAnotherGoogleAccount() {
        String currentId = UUID.randomUUID().toString(), targetId = UUID.randomUUID().toString();
        String currentEmail = email(), targetEmail = email(), subject = UUID.randomUUID().toString();
        insertUser(currentId, currentEmail, null, null, false);
        insertUser(targetId, targetEmail, null, null, false);
        rejects(403, () -> linker.link(currentId, subject, targetEmail, "Name", "refresh"));
        assertNull(subject(currentId));
        assertNull(subject(targetId));
        assertNull(store.user(targetId).refreshToken());
    }

    @Test void demoOrMissingCurrentUserCannotLinkGoogleEvenWithMatchingEmail() {
        String id = UUID.randomUUID().toString(), email = email(), subject = UUID.randomUUID().toString();
        insertUser(id, email, null, null, true);
        rejects(403, () -> linker.link(id, subject, email, "Name", "refresh"));
        rejects(403, () -> linker.link("missing-" + id, subject, email, "Name", "refresh"));
        assertNull(subject(id));
        assertNull(store.user(id).refreshToken());
    }

    @Test void googleSubjectBoundToAnotherUserCannotBeStolenByMatchingTargetEmail() {
        String ownerId = UUID.randomUUID().toString(), localId = UUID.randomUUID().toString();
        String subject = UUID.randomUUID().toString(), localEmail = email();
        insertUser(ownerId, email(), subject, crypto.encrypt("owner-token"), false);
        insertUser(localId, localEmail, null, null, false);
        rejects(403, () -> linker.link(localId, subject, localEmail, "Name", "attacker-token"));
        assertEquals(subject, subject(ownerId));
        assertEquals("owner-token", crypto.decrypt(store.user(ownerId).refreshToken()));
        assertNull(subject(localId));
        assertNull(store.user(localId).refreshToken());
    }

    @Test void differentGoogleSubjectCannotReplaceAnExistingLink() {
        String id = UUID.randomUUID().toString(), email = email(), boundSubject = UUID.randomUUID().toString();
        insertUser(id, email, boundSubject, crypto.encrypt("saved-token"), false);
        rejects(403, () -> linker.link(id, UUID.randomUUID().toString(), email, "Name", "new-token"));
        assertEquals(boundSubject, subject(id));
        assertEquals("saved-token", crypto.decrypt(store.user(id).refreshToken()));
    }

    @Test void boundSubjectWithChangedEmailIsDeniedWithoutCreatingAnotherUser() {
        String id = UUID.randomUUID().toString(), subject = UUID.randomUUID().toString(), changedEmail = email();
        insertUser(id, email(), subject, crypto.encrypt("saved-token"), false);
        rejects(403, () -> linker.link(null, subject, changedEmail, "Name", "new-token"));
        assertEquals(0, usersWithEmail(changedEmail));
        assertEquals("saved-token", crypto.decrypt(store.user(id).refreshToken()));
    }

    @Test void ambiguousRealEmailsFailClosedEvenWhenOneMatchingUserIsSignedIn() {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString(), email = email();
        insertUser(first, email, null, null, false);
        insertUser(second, email.toUpperCase(), null, null, false);
        String subject = UUID.randomUUID().toString();
        rejects(403, () -> linker.link(null, subject, email, "Name", "refresh"));
        rejects(403, () -> linker.link(first, subject, email, "Name", "refresh"));
        assertNull(subject(first));
        assertNull(subject(second));
        assertEquals(2, usersWithEmail(email));
    }

    @Test void anUnlinkedUserIdCollisionCannotOverwriteThatUser() {
        String id = UUID.randomUUID().toString(), email = email();
        insertUser(id, email(), null, null, false);
        rejects(403, () -> linker.link(null, id, email, "Name", "refresh"));
        assertNull(subject(id));
        assertNull(store.user(id).refreshToken());
        assertEquals(0, usersWithEmail(email));
    }

    private void insertUser(String id, String email, String subject, String refresh, boolean demo) {
        store.db.update("INSERT INTO app_user(id,email,display_name,google_subject,refresh_token,demo) VALUES(?,?,?,?,?,?)",
                id, email, "Original name", subject, refresh, demo);
    }

    private String insertEvent(String user, int index) {
        String id = UUID.randomUUID().toString();
        store.db.update("INSERT INTO calendar_event(id,user_id,google_id,etag,payload,sync_state,version) VALUES(?,?,?,?,?,'SYNCED',7)",
                id, user, "google-" + index, "etag-" + index, Json.write(Json.object().put("title", "Event " + index)));
        return id;
    }

    private String insertActivity(String user, String event) {
        String id = UUID.randomUUID().toString();
        store.db.update("INSERT INTO activity_record(id,user_id,event_id,event_title,payload,version,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,3,?,?)", id, user, event, "Original event", "{\"content\":\"Saved activity\"}",
                "2026-09-25T12:00:00+09:00", "2026-09-25T13:00:00+09:00");
        return id;
    }

    private Map<String, Object> record(String id) {
        return store.db.queryForMap("SELECT * FROM activity_record WHERE id=?", id);
    }

    private String subject(String id) {
        return store.db.queryForObject("SELECT google_subject FROM app_user WHERE id=?", String.class, id);
    }

    private int usersWithEmail(String email) {
        return store.db.queryForObject("SELECT COUNT(*) FROM app_user WHERE LOWER(email)=LOWER(?)", Integer.class, email);
    }

    private String email() { return UUID.randomUUID() + "@example.com"; }

    private void rejects(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ApiException.class, action).status);
    }
}
