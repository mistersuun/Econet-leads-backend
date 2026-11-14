package com.econet.leads.sms;

import com.econet.leads.util.PhoneFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Twilio Programmable Messaging over plain HTTP (no SDK):
 * {@code POST {base}/2010-04-01/Accounts/{sid}/Messages.json}, basic auth, form body To/From/Body.
 * Never logs the token, the body or a full phone number.
 */
@Service
@Slf4j
public class SmsService {

    /** Outcome of one send; {@code error} is a short message safe to show to a user. */
    public record Result(boolean sent, String sid, String error) {
    }

    private final SmsSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public SmsService(SmsSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public boolean isConfigured() {
        return settings.isConfigured();
    }

    /** E.164 for a Canadian / US number (+1XXXXXXXXXX); an already international +number is kept. Null when unusable. */
    public static String toE164(String phone) {
        if (phone == null) return null;
        String trimmed = phone.trim();
        if (trimmed.startsWith("+")) {
            String digits = trimmed.replaceAll("\\D", "");
            return digits.length() >= 8 && digits.length() <= 15 ? "+" + digits : null;
        }
        String digits = PhoneFormatter.normalize(trimmed);
        return digits != null && digits.length() == 10 ? "+1" + digits : null;
    }

    /** "+1514•••1234" style: only the last 4 digits survive. */
    public static String mask(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() <= 4) return "****";
        return "***" + digits.substring(digits.length() - 4);
    }

    public Result send(String toE164, String body) {
        if (!settings.isConfigured()) {
            return new Result(false, null, "SMS non configuré");
        }
        try {
            String form = "To=" + enc(toE164) + "&From=" + enc(settings.getFrom().trim()) + "&Body=" + enc(body);
            String auth = Base64.getEncoder().encodeToString(
                    (settings.getAccountSid().trim() + ":" + settings.getAuthToken().trim()).getBytes(StandardCharsets.UTF_8));
            String base = settings.getApiBase().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/2010-04-01/Accounts/"
                            + enc(settings.getAccountSid().trim()) + "/Messages.json"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = parse(response.body());
            if (response.statusCode() / 100 == 2) {
                String sid = json != null && json.hasNonNull("sid") ? json.get("sid").asText() : null;
                return new Result(true, sid, null);
            }
            String detail = json != null && json.hasNonNull("message") ? json.get("message").asText() : "HTTP " + response.statusCode();
            log.warn("SMS to {} refused by the provider (HTTP {})", mask(toE164), response.statusCode());
            return new Result(false, null, truncate(detail, 300));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, null, "Envoi interrompu");
        } catch (IOException | RuntimeException e) {
            log.warn("SMS to {} failed: {}", mask(toE164), e.getClass().getSimpleName());
            return new Result(false, null, "Fournisseur SMS injoignable");
        }
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : mapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
