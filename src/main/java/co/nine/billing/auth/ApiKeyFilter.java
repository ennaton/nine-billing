package co.nine.billing.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves X-Api-Key to a tenant and binds it for the request. Anything under
 * /v1 without a valid key is 401 problem+json. Health and the key bootstrap
 * endpoint are outside /v1 on purpose.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final ApiKeyRepository keys;

    public ApiKeyFilter(ApiKeyRepository keys) {
        this.keys = keys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !p.startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader(HEADER);
        Optional<UUID> tenant = (key == null || key.isBlank()) ? Optional.empty() : keys.tenantFor(key);

        if (tenant.isEmpty()) {
            res.setStatus(401);
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            res.getWriter().write("""
                {"type":"about:blank","title":"Unauthorized","status":401,"detail":"missing or invalid X-Api-Key","instance":"%s"}
                """.formatted(req.getRequestURI()).trim());
            return;
        }

        TenantContext.bind(tenant.get());
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
