package com.econet.leads.controller;

import com.econet.leads.dto.NotificationDTO;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Follow-ups due/overdue, expired claims, tenders closing soon")
public class NotificationController {

    private final NotificationService notificationService;
    private final AuthenticationFacade authenticationFacade;

    @GetMapping
    @Operation(summary = "Notifications of the current user", description = "Sorted by time; poll every 60 s")
    public ResponseEntity<List<NotificationDTO>> list() {
        return ResponseEntity.ok(notificationService.forUser(authenticationFacade.getCurrentUser()));
    }
}
