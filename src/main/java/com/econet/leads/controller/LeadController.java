package com.econet.leads.controller;

import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.dto.LogCallRequest;
import com.econet.leads.dto.LogCallResponse;
import com.econet.leads.model.User;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.LeadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
@Tag(name = "Leads", description = "Calling workflow: call queue and call logging")
public class LeadController {

    private final LeadService leadService;
    private final AuthenticationFacade authenticationFacade;

    @GetMapping("/queue")
    @Operation(summary = "Call queue", description = "Follow-ups due (oldest first), then NEW leads with a phone (best quality first). Excludes WON/LOST/DO_NOT_CALL and leads assigned to someone else.")
    public ResponseEntity<List<BusinessDTO>> getQueue(@RequestParam(defaultValue = "20") int size) {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.ok(leadService.getQueue(user.getId(), size));
    }

    @PostMapping("/{id}/calls")
    @Operation(summary = "Log a call", description = "Creates the call contact, updates status, counters, assignment and next follow-up")
    public ResponseEntity<LogCallResponse> logCall(@PathVariable UUID id, @Valid @RequestBody LogCallRequest request) {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.ok(leadService.logCall(id, request, user.getId()));
    }
}
