package com.econet.leads.controller;

import com.econet.leads.dto.TargetsDTO;
import com.econet.leads.dto.UserSummaryDTO;
import com.econet.leads.exception.ApiException;
import com.econet.leads.repository.UserRepository;
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

    private final UserRepository userRepository;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List users with their daily targets (ADMIN)")
    public ResponseEntity<java.util.List<UserSummaryDTO>> list() {
        return ResponseEntity.ok(userRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(u -> u.getUsername().toLowerCase(java.util.Locale.ROOT)))
                .map(u -> {
                    TargetsDTO.Targets t = TargetsService.of(u);
                    return new UserSummaryDTO(u.getId(), u.getUsername(), u.getEmail(), u.getRole().name(),
                            !Boolean.FALSE.equals(u.getActive()), t.callTarget(), t.conversationTarget(), u.getCreatedAt());
                })
                .toList());
    }

    @GetMapping("/{id}/targets")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "A user's daily targets (ADMIN)")
    public ResponseEntity<TargetsDTO.Targets> getTargets(@PathVariable UUID id) {
        return ResponseEntity.ok(TargetsService.of(userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("User not found: " + id))));
    }

    @PutMapping("/{id}/targets")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Set a user's daily targets (ADMIN)")
    public ResponseEntity<TargetsDTO.Targets> updateTargets(@PathVariable UUID id, @Valid @RequestBody TargetsDTO.Update request) {
        return ResponseEntity.ok(targetsService.update(id, request));
    }
}
