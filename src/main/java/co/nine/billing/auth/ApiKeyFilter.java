package co.nine.billing.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves X-Api-Key to a tenant and binds it for the request.
 *
 * <p>The filter denies by default. Every request needs a key unless its path is
 * one this service deliberately serves without one, so an unknown path is
 * authenticated rather than waved through and a new endpoint is covered the day
 * it is written rather than the day someone remembers to guard it. The earlier
 * rule was the other way round, "skip unless the path starts with /v1/", which
 * made every route outside that prefix open by omission.
 *
 * <p>The decision is made on the path Spring routes on, never on the raw request
 * URI. They are two different strings. The raw URI keeps path parameters, the
 * {@code ;name=value} suffix a segment may carry, and Spring removes them before
 * matching a handler; it also keeps the servlet context path, which Spring
 * removes too. Deciding on the raw URI let {@code /v1;x=1/...} reach a /v1
 * handler while this filter believed the request was not a /v1 request at all,
 * and it would have disabled the filter service wide the day a context path or
 * an ingress prefix appeared in front of the service.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    /**
     * Paths served without a tenant key. Operator endpoints under /admin carry
     * their own bootstrap-secret check inside the handler, and actuator is a
     * separate exposure decision. Everything not listed here needs a key.
     */
    private static final List<String> OPEN = List.of("/actuator", "/admin");

    private final ApiKeyRepository keys;

    public ApiKeyFilter(ApiKeyRepository keys) {
        this.keys = keys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = routedPath(req);
        for (String open : OPEN) {
            if (path.equals(open) || path.startsWith(open + "/")) return true;
        }
        return false;
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
                """.formatted(routedPath(req)).trim());
            return;
        }

        TenantContext.bind(tenant.get());
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The path Spring will route on: context path removed, path parameters
     * removed, repeated separators collapsed.
     *
     * <p>Rebuilt from the parsed segments rather than read off the container,
     * because {@code PathContainer.value()} still carries the parameters and
     * only {@code PathSegment.valueToMatch()} is what a handler mapping is
     * compared against. {@code parse} rather than {@code getParsedRequestPath}
     * because a filter runs before the DispatcherServlet fills that cache.
     */
    private static String routedPath(HttpServletRequest req) {
        StringBuilder path = new StringBuilder();
        for (PathContainer.Element element : ServletRequestPathUtils.parse(req).pathWithinApplication().elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                path.append('/').append(segment.valueToMatch());
            }
        }
        return path.isEmpty() ? "/" : path.toString();
    }
}
