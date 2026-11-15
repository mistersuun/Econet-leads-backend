package com.econet.leads.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client address for rate limiting and hashing, never for authorization. Behind Render the
 * container's Tomcat resolves X-Forwarded-For itself (server.forward-headers-strategy=native, a
 * RemoteIpValve that takes the right-most entry added by a proxy we do not trust), so the socket
 * address is already the real client; the raw header is never read here because its left-most
 * entries are client-controlled.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
