package com.econet.leads.controller;

import com.econet.leads.dto.TargetsDTO;
import com.econet.leads.service.TargetsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User administration")
public class UserController {

    private final TargetsService targetsService;

    @PutMapping("/{id}/targets")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Set a user's daily targets (ADMIN)")
    public ResponseEntity<TargetsDTO.Targets> updateTargets(@PathVariable UUID id, @Valid @RequestBody TargetsDTO.Update request) {
        return ResponseEntity.ok(targetsService.update(id, request));
    }
}
