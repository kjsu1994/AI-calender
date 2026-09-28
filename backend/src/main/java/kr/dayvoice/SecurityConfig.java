package kr.dayvoice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http,LocalAuth localAuth) throws Exception {
        // OAuth is handled by AuthController; API ownership is enforced via the server session.
        return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
            .csrf(c -> {})
            .addFilterBefore(new PersistentLoginFilter(localAuth),CsrfFilter.class)
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
            .build();
    }
}
