package com.econet.leads.controller;

import com.econet.leads.model.User;
import com.econet.leads.realtime.SseEventService;
import com.econet.leads.security.AuthenticationFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/events?token=<accessToken>}: the JWT comes as a query parameter because
 * EventSource cannot set headers. {@link com.econet.leads.security.JwtAuthenticationFilter} accepts
 * the parameter for this path only, so the stream is protected like every other GET (all roles,
 * VIEWER included). The token is never logged.
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Tag(name = "Events", description = "Server-sent events: presence, claims, calls, lead and tender updates")
public class SseController {

    private final SseEventService sseEventService;
    private final AuthenticationFacade authenticationFacade;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Event stream", description = "text/event-stream; heartbeat comment every 25 s; Last-Event-ID replays from an in-memory ring of 200 events")
    public SseEmitter events(@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        User user = authenticationFacade.getCurrentUser();
        return sseEventService.connect(user.getId(), user.getUsername(), parseEventId(lastEventId));
    }

    private static Long parseEventId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
