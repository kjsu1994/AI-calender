package kr.dayvoice;

import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class GoogleAccountLinker {
    private final Store store;
    private final Crypto crypto;
    private final TransactionTemplate tx;

    GoogleAccountLinker(Store store, Crypto crypto, TransactionTemplate tx) {
        this.store = store;
        this.crypto = crypto;
        this.tx = tx;
    }

    // Serialize account creation as well as lookup: an absent email has no row to lock.
    synchronized String link(String currentUserId, String subject, String verifiedEmail,
                             String displayName, String refreshToken) {
        if (subject == null || subject.isBlank() || subject.length() > 100
                || verifiedEmail == null || verifiedEmail.isBlank() || verifiedEmail.length() > 320)
            throw denied();
        String email = verifiedEmail.trim().toLowerCase(Locale.ROOT);
        try {
            return tx.execute(status -> linkInTransaction(currentUserId, subject, email, displayName, refreshToken));
        } catch (DuplicateKeyException e) {
            // Unique subject and primary-key constraints also protect concurrent external writers.
            throw denied();
        }
    }

    private String linkInTransaction(String currentUserId, String subject, String email,
                                     String displayName, String refreshToken) {
        List<Account> matching = accounts("LOWER(email)=? AND demo=FALSE", email);
        if (matching.size() > 1) throw denied();
        Account target = matching.isEmpty() ? null : matching.getFirst();

        if (currentUserId != null) {
            List<Account> current = accounts("id=?", currentUserId);
            if (current.isEmpty() || current.getFirst().demo()
                    || !current.getFirst().email().equalsIgnoreCase(email)
                    || target == null || !target.id().equals(currentUserId))
                throw denied();
        }

        List<Account> bound = accounts("google_subject=?", subject);
        if (!bound.isEmpty() && (target == null || !bound.getFirst().id().equals(target.id())
                || !bound.getFirst().email().equalsIgnoreCase(email)))
            throw denied();
        if (target != null && target.subject() != null && !target.subject().equals(subject))
            throw denied();

        boolean hasNewToken = refreshToken != null && !refreshToken.isBlank();
        if (!hasNewToken && (target == null || target.refreshToken() == null || target.refreshToken().isBlank()))
            throw new ApiException(400, "Google 캘린더 연결 권한에 다시 동의해 주세요.");

        if (target == null) {
            // Legacy IDs can equal Google subjects, including rows that are not linked yet.
            if (!accounts("id=?", subject).isEmpty()) throw denied();
            String name = displayName == null || displayName.isBlank() ? email : displayName;
            if (name.length() > 200) name = name.substring(0, 200);
            store.db.update("INSERT INTO app_user(id,email,display_name,google_subject,refresh_token,sync_status) "
                    + "VALUES(?,?,?,?,?,'DISCONNECTED')", subject, email, name, subject, crypto.encrypt(refreshToken));
            return subject;
        }

        String encrypted = hasNewToken ? crypto.encrypt(refreshToken) : target.refreshToken();
        store.db.update("UPDATE app_user SET google_subject=?,refresh_token=?,"
                + "sync_status=CASE WHEN calendar_id IS NULL THEN 'DISCONNECTED' ELSE 'PENDING' END WHERE id=?",
                subject, encrypted, target.id());
        return target.id();
    }

    private List<Account> accounts(String condition, String value) {
        return store.db.query("SELECT id,email,google_subject,refresh_token,demo FROM app_user WHERE "
                        + condition + " FOR UPDATE", (rs, row) -> new Account(rs.getString("id"),
                        rs.getString("email"), rs.getString("google_subject"), rs.getString("refresh_token"),
                        rs.getBoolean("demo")), value);
    }

    private ApiException denied() {
        return new ApiException(403, "현재 계정과 같은 이메일의 Google 계정만 연결할 수 있습니다. 계정 연결 정보를 확인해 주세요.");
    }

    private record Account(String id, String email, String subject, String refreshToken, boolean demo) {}
}
