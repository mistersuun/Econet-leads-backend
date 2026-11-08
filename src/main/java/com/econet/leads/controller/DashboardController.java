package com.econet.leads.controller;

import com.econet.leads.dto.DashboardDTOs;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Dashboard aggregates. {@code from}/{@code to} are optional YYYY-MM-DD dates (inclusive);
 * default is the last 30 days including today (America/Montreal).
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Aggregated KPIs for the CRM dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final AuthenticationFacade authenticationFacade;

    @GetMapping("/summary")
    @Operation(summary = "KPI summary")
    public ResponseEntity<DashboardDTOs.Summary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(dashboardService.getSummary(dashboardService.resolveRange(from, to)));
    }

    @GetMapping("/pipeline")
    @Operation(summary = "Lead count per status (all statuses, pipeline order)")
    public ResponseEntity<List<DashboardDTOs.PipelineStage>> pipeline(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // The pipeline is a snapshot of current statuses; from/to are accepted but not used.
        return ResponseEntity.ok(dashboardService.getPipeline());
    }

    @GetMapping("/activity")
    @Operation(summary = "Calls, conversations and wins per day (zero-filled)")
    public ResponseEntity<List<DashboardDTOs.ActivityDay>> activity(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(dashboardService.getActivity(dashboardService.resolveRange(from, to)));
    }

    @GetMapping("/breakdown")
    @Operation(summary = "Leads by type, city or source", description = "dimension=type|city|source, ordered by total desc")
    public ResponseEntity<List<DashboardDTOs.BreakdownRow>> breakdown(
            @RequestParam(defaultValue = "type") String dimension,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(dashboardService.getBreakdown(dimension, limit));
    }

    @GetMapping("/outcomes")
    @Operation(summary = "Call outcomes in the range")
    public ResponseEntity<List<DashboardDTOs.OutcomeCount>> outcomes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(dashboardService.getOutcomes(dashboardService.resolveRange(from, to)));
    }

    @GetMapping("/leaderboard")
    @Operation(summary = "Calls, conversations and wins per user, by calls desc")
    public ResponseEntity<List<DashboardDTOs.LeaderboardRow>> leaderboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(dashboardService.getLeaderboard(dashboardService.resolveRange(from, to)));
    }

    // --- addendum 3 ---

    @GetMapping("/conversion-by-type")
    @Operation(summary = "Observed conversion per business type (last 180 days, what the queue priority learned)")
    public ResponseEntity<List<DashboardDTOs.ConversionByType>> conversionByType() {
        return ResponseEntity.ok(dashboardService.getConversionByType());
    }

    @GetMapping("/funnel")
    @Operation(summary = "Dials > connects > conversations > interested > quotes > won in the range")
    public ResponseEntity<DashboardDTOs.Funnel> funnel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(dashboardService.getFunnel(dashboardService.resolveRange(from, to)));
    }

    @GetMapping("/best-hours")
    @Operation(summary = "Calls, connects and positive outcomes per weekday and hour (Mon-Fri 8-18, Montreal time, zero-filled)")
    public ResponseEntity<List<DashboardDTOs.BestHour>> bestHours(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(dashboardService.getBestHours(dashboardService.resolveRange(from, to)));
    }

    @GetMapping("/me/today")
    @Operation(summary = "My scoreboard: today's calls/conversations/quotes/wins, targets, streak, best day, rank")
    public ResponseEntity<DashboardDTOs.MeToday> meToday() {
        return ResponseEntity.ok(dashboardService.getMeToday(authenticationFacade.getCurrentUser()));
    }

    @GetMapping("/team/today")
    @Operation(summary = "Team scoreboard for today with live presence")
    public ResponseEntity<List<DashboardDTOs.TeamToday>> teamToday() {
        return ResponseEntity.ok(dashboardService.getTeamToday());
    }
}
