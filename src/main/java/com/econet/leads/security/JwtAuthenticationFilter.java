package com.econet.leads.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * The one path that may carry the access token as a query parameter: the SSE stream, because
     * EventSource cannot set an Authorization header. The value is never logged.
     */
    static final String SSE_PATH = "/api/events";
    static final String SSE_TOKEN_PARAM = "token";

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);
            log.debug("JWT from request: {}", jwt != null ? "present" : "absent");

            if (StringUtils.hasText(jwt)) {
                log.debug("Validating JWT token");
                boolean isValid = tokenProvider.validateToken(jwt);
                log.debug("JWT validation result: {}", isValid);

                if (isValid) {
                    String username = tokenProvider.getUsernameFromToken(jwt);
                    log.debug("Username from token: {}", username);

                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                    log.debug("UserDetails loaded. Authorities: {}", userDetails.getAuthorities());

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("Authentication set in SecurityContext for user: {}", username);
                }
            }
        } catch (Exception ex) {
            log.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        if (isSsePath(request)) {
            String token = request.getParameter(SSE_TOKEN_PARAM);
            if (StringUtils.hasText(token)) {
                return token;
            }
        }
        return null;
    }

    private static boolean isSsePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return SSE_PATH.equals(uri) && "GET".equalsIgnoreCase(request.getMethod());
    }
}
