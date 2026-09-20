package dev.sakshi.milestoneledger.shared.web;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String REQUEST_ID_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".requestId";
    private static final String REQUEST_ID_MDC_KEY = "requestId";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<String> COLLECTION_PATHS = Set.of(
            "/api/v1/demands", "/api/v1/receipts", "/api/v1/exceptions", "/api/v1/financial-entries");
    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private final String applicationVersion;

    public CorrelationIdFilter(@Value("${info.app.version:unknown}") String applicationVersion) {
        this.applicationVersion = applicationVersion;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = requestId(request.getHeader(REQUEST_ID_HEADER));
        long startedAt = System.nanoTime();
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
            if (!isSuccessfulHealthProbe(request, response) && !isSuccessfulCollectionRead(request, response)) {
                LOGGER.atInfo()
                        .addKeyValue("httpMethod", request.getMethod())
                        .addKeyValue("httpPath", request.getRequestURI())
                        .addKeyValue("httpStatus", response.getStatus())
                        .addKeyValue("durationMs", durationMillis)
                        .addKeyValue("applicationVersion", applicationVersion)
                        .log("http_request_completed");
            }
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    public static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value instanceof String string ? string : requestId(request.getHeader(REQUEST_ID_HEADER));
    }

    static boolean isSuccessfulHealthProbe(HttpServletRequest request, HttpServletResponse response) {
        return response.getStatus() < 400 && request.getRequestURI().matches("/(api/v1/health|actuator/health|livez|readyz).*");
    }

    static boolean isSuccessfulCollectionRead(HttpServletRequest request, HttpServletResponse response) {
        return response.getStatus() < 400 && "GET".equals(request.getMethod())
                && COLLECTION_PATHS.contains(request.getRequestURI());
    }

    private static String requestId(String suppliedRequestId) {
        if (suppliedRequestId != null && SAFE_REQUEST_ID.matcher(suppliedRequestId).matches()) {
            return suppliedRequestId;
        }
        return UUID.randomUUID().toString();
    }
}
