package com.econet.leads.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Best-effort client address behind a reverse proxy (Render): the right-most X-Forwarded-For entry
 * that is not a private / loopback address (the left-most ones are client-controlled), else the
 * socket address. Used for rate limiting and for hashing, never for authorization.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = parts[i].trim();
                if (!candidate.isEmpty() && candidate.matches("[0-9a-fA-F:.]+") && !isPrivate(candidate)) {
                    return candidate;
                }
            }
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private static boolean isPrivate(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            return a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc);
        } catch (UnknownHostException e) {
            return true;
        }
    }
}
