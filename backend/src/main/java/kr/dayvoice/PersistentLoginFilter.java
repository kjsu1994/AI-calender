package kr.dayvoice;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

// Instantiated only inside the SecurityFilterChain, never registered as a servlet filter bean.
final class PersistentLoginFilter extends OncePerRequestFilter {
    private final LocalAuth localAuth;

    PersistentLoginFilter(LocalAuth localAuth) { this.localAuth = localAuth; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        localAuth.restore(request, response);
        chain.doFilter(request, response);
    }
}
