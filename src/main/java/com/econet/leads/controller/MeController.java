package com.econet.leads.controller;

import com.econet.leads.dto.TargetsDTO;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.TargetsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
@Tag(name = "Me", description = "Settings of the current user")
public class MeController {

    private final TargetsService targetsService;
    private final AuthenticationFacade authenticationFacade;

    @GetMapping("/targets")
    @Operation(summary = "My daily targets", description = "{ callTarget, conversationTarget }")
    public ResponseEntity<TargetsDTO.Targets> targets() {
        return ResponseEntity.ok(TargetsService.of(authenticationFacade.getCurrentUser()));
    }

    @PutMapping("/targets")
    @Operation(summary = "Set my daily targets", description = "{ callTarget?, conversationTarget? } (1–500)")
    public ResponseEntity<TargetsDTO.Targets> update(@Valid @RequestBody TargetsDTO.Update request) {
        return ResponseEntity.ok(targetsService.update(authenticationFacade.getCurrentUser().getId(), request));
    }
}
