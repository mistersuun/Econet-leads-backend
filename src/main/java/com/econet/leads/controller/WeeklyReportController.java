package com.econet.leads.controller;

import com.econet.leads.config.AsyncConfig;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.User;
import com.econet.leads.report.WeeklyReportService;
import com.econet.leads.security.AuthenticationFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import com.econet.leads.repository.UserRepository;

import java.util.Map;
import java.util.concurrent.Executor;

/** Weekly coaching email: preview (self), send now (ADMIN) and the per-user opt-out. */
@RestController
@Slf4j
@Tag(name = "Weekly report", description = "Monday coaching email")
public class WeeklyReportController {

    private final WeeklyReportService service;
    private final AuthenticationFacade authenticationFacade;
    private final UserRepository userRepository;
    private final Executor executor;

    public WeeklyReportController(WeeklyReportService service, AuthenticationFacade authenticationFacade,
                                  UserRepository userRepository,
                                  @Qualifier(AsyncConfig.IMPORT_EXECUTOR) Executor executor) {
        this.service = service;
        this.authenticationFacade = authenticationFacade;
        this.userRepository = userRepository;
        this.executor = executor;
    }

    public record Preferences(boolean weeklyReport) {
    }

    public record PreferencesUpdate(@NotNull(message = "La préférence weeklyReport est obligatoire") Boolean weeklyReport) {
    }

    @PostMapping("/api/reports/weekly/preview")
    @Operation(summary = "Preview my weekly coaching email", description = "Renders last week's report for the current user; works without SMTP")
    public ResponseEntity<WeeklyReportService.Rendered> preview() {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.ok(service.render(service.build(user)));
    }

    @PostMapping("/api/reports/weekly/send-now")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Send the weekly coaching email now (ADMIN)", description = "202 and sends in the background; 503 when SMTP is not configured")
    public ResponseEntity<Map<String, Object>> sendNow() {
        if (!service.mailConfigured()) {
            throw ApiException.serviceUnavailable("Le courriel n'est pas configuré (SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD)");
        }
        executor.execute(() -> {
            try {
                service.sendAll();
            } catch (RuntimeException e) {
                log.warn("Manual weekly report run failed: {}", e.getClass().getSimpleName());
            }
        });
        return ResponseEntity.accepted().body(Map.of("status", "queued"));
    }

    @GetMapping("/api/me/preferences")
    @Operation(summary = "My preferences", description = "{ weeklyReport }")
    public ResponseEntity<Preferences> preferences() {
        return ResponseEntity.ok(new Preferences(!Boolean.FALSE.equals(authenticationFacade.getCurrentUser().getWeeklyReport())));
    }

    @PutMapping("/api/me/preferences")
    @Transactional
    @Operation(summary = "Update my preferences", description = "{ weeklyReport: boolean } turns the Monday coaching email on or off")
    public ResponseEntity<Preferences> updatePreferences(@Valid @RequestBody PreferencesUpdate request) {
        User user = userRepository.findById(authenticationFacade.getCurrentUser().getId())
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable"));
        user.setWeeklyReport(request.weeklyReport());
        userRepository.saveAndFlush(user);
        return ResponseEntity.ok(new Preferences(request.weeklyReport()));
    }
}
