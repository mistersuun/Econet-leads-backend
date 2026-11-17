package com.econet.leads.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Builds the public links of quotes and booking pages and generates / compares their secrets.
 * Base URL: {@code app.public-url} (env APP_PUBLIC_URL) when set, else derived from the current
 * request (honouring X-Forwarded-Proto / -Host), else http://localhost:8080.
 */
@Service
public class PublicLinkService {

    public static final String FALLBACK_BASE = "http://localhost:8080";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String configuredBase;
    private final byte[] hmacKey;

    public PublicLinkService(@Value("${app.public-url:}") String configuredBase,
                             @Value("${app.jwt.secret:}") String hashSecret) {
        this.configuredBase = configuredBase == null ? "" : configuredBase.trim();
        if (hashSecret == null || hashSecret.isBlank()) {
            this.hmacKey = new byte[32];
            RANDOM.nextBytes(this.hmacKey);
        } else {
            this.hmacKey = ("public-link-hash|" + hashSecret).getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Keyed hash (HMAC-SHA-256) for values that must not be recoverable by brute force, such as an IP address. */
    public String hmacHex(String value) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 32 random bytes as base64url without padding (43 chars). */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** True when the token has the shape produced by {@link #newToken()} (rejects junk before any lookup). */
    public static boolean looksLikeToken(String token) {
        return token != null && token.length() == 43 && token.matches("[A-Za-z0-9_-]{43}");
    }

    /** Constant-time comparison of a stored and a presented secret. */
    public static boolean secretsEqual(String stored, String presented) {
        if (stored == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        if (!configuredBase.isEmpty()) {
            return stripTrailingSlash(configuredBase);
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return fromRequest(servlet.getRequest());
        }
        return FALLBACK_BASE;
    }

    static String fromRequest(HttpServletRequest request) {
        String proto = firstValue(request.getHeader("X-Forwarded-Proto"));
        String host = firstValue(request.getHeader("X-Forwarded-Host"));
        if (proto == null) proto = request.getScheme();
        if (host == null) {
            host = request.getServerName() + (isDefaultPort(proto, request.getServerPort()) ? "" : ":" + request.getServerPort());
        }
        if (!proto.matches("https?") || !host.matches("[A-Za-z0-9.:\\[\\]-]+")) {
            return FALLBACK_BASE;
        }
        return proto + "://" + host;
    }

    public String quoteUrl(String token) {
        return baseUrl() + "/public/quotes/" + token;
    }

    public String quotePdfUrl(String token) {
        return baseUrl() + "/public/quotes/" + token + "/pdf";
    }

    public String bookingUrl(String token) {
        return baseUrl() + "/public/book/" + token;
    }

    private static boolean isDefaultPort(String proto, int port) {
        return ("http".equals(proto) && port == 80) || ("https".equals(proto) && port == 443);
    }

    private static String firstValue(String header) {
        if (header == null || header.isBlank()) return null;
        return header.split(",")[0].trim();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
