package com.civicworks.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class MdcLoggingFilter implements Filter {

    private static final String REQUEST_ID = "requestId";
    private static final String USER_ID = "userId";
    private static final String METHOD = "method";
    private static final String PATH = "path";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MDC.put(REQUEST_ID, requestId);
        MDC.put(METHOD, httpRequest.getMethod());
        MDC.put(PATH, httpRequest.getRequestURI());

        // Set response header so clients can correlate
        httpResponse.setHeader("X-Request-Id", requestId);

        try {
            chain.doFilter(request, response);
            // Try to enrich MDC with auth principal after filter chain runs
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                MDC.put(USER_ID, auth.getName());
            }
        } finally {
            MDC.clear();
        }
    }
}
