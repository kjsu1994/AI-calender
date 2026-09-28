package kr.dayvoice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {
    public record Settings(String baseUrl, boolean demoMode, String allowedEmails,
        String encryptionKey, String googleClientId, String googleClientSecret,
        String geminiApiKey, String geminiModel, String ffmpeg) {}
    @org.springframework.context.annotation.Bean
    Settings settings(org.springframework.core.env.Environment env) {
        return new Settings(env.getProperty("app.base-url"), env.getProperty("app.demo-mode", Boolean.class, false),
            env.getProperty("app.allowed-emails", ""), env.getProperty("app.encryption-key", ""),
            env.getProperty("app.google-client-id", ""), env.getProperty("app.google-client-secret", ""),
            env.getProperty("app.gemini-api-key", ""), env.getProperty("app.gemini-model"), env.getProperty("app.ffmpeg", "ffmpeg"));
    }
}
