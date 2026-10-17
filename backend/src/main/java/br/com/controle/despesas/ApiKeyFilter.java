package br.com.controle.despesas;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    @Value("${APP_API_KEY:}")
    private String configuredApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        if ("OPTIONS".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (configuredApiKey.isBlank()) {
            allowErrorFromApp(request, response);
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Configure APP_API_KEY no servidor");
            return;
        }
        String provided = request.getHeader("X-API-Key");
        if (provided != null && MessageDigest.isEqual(configuredApiKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            filterChain.doFilter(request, response);
            return;
        }
        allowErrorFromApp(request, response);
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Chave invalida");
    }

    // Match the controllers' public-origin policy even when auth stops before MVC.
    // No credentials/cookies are allowed; the API key remains mandatory.
    private void allowErrorFromApp(HttpServletRequest request, HttpServletResponse response) {
        if (request.getHeader("Origin") != null) response.setHeader("Access-Control-Allow-Origin", "*");
    }
}
