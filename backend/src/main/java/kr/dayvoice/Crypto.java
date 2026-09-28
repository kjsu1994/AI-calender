package kr.dayvoice;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
class Crypto {
    private final SecretKeySpec key;
    Crypto(AppConfig.Settings settings) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(settings.encryptionKey()); }
        catch (Exception e) { throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must be Base64 encoded"); }
        if (bytes.length != 32) throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must contain 32 random bytes; run scripts/setup.ps1");
        key = new SecretKeySpec(bytes, "AES");
    }
    String encrypt(String value) {
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Token encryption failed"); }
    }
    String decrypt(String value) {
        try {
            String[] parts = value.split("\\.");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(c.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new ApiException(503, "토큰을 읽지 못했습니다. Google 계정을 다시 연결해 주세요."); }
    }
}
