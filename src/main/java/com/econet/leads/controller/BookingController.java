package com.econet.leads.controller;

import com.econet.leads.dto.BookingDTOs;
import com.econet.leads.model.User;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Visits", description = "Booking links, visits and weekly availability")
public class BookingController {

    private final BookingService bookingService;
    private final AuthenticationFacade authenticationFacade;

    @PostMapping("/leads/{id}/booking-links")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Create a booking link", description = "Single use, valid 14 days: { url, expiresAt }")
    public ResponseEntity<BookingDTOs.LinkResponse> createLink(@PathVariable UUID id) {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.status(HttpStatus.CREATED).body(bookingService.createLink(id, user));
    }

    @GetMapping("/visits")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Visits between two dates", description = "from / to (yyyy-MM-dd, inclusive); default today .. +30 days")
    public ResponseEntity<List<BookingDTOs.Visit>> visits(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(bookingService.visits(from, to));
    }

    @PatchMapping("/visits/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Change a visit's status", description = "{ status: booked | done | cancelled }")
    public ResponseEntity<BookingDTOs.Visit> updateVisit(@PathVariable UUID id, @Valid @RequestBody BookingDTOs.VisitStatusRequest request) {
        return ResponseEntity.ok(bookingService.updateVisitStatus(id, request.status()));
    }

    @GetMapping("/availability")
    @Operation(summary = "Weekly availability", description = "Windows in which visits can be booked (weekday 1 = Monday .. 7 = Sunday, Montreal time)")
    public ResponseEntity<List<BookingDTOs.Window>> availability() {
        return ResponseEntity.ok(bookingService.availability());
    }

    @PutMapping("/availability")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace the weekly availability (ADMIN)", description = "[{ weekday, startTime HH:mm, endTime HH:mm, slotMinutes, active }]")
    public ResponseEntity<List<BookingDTOs.Window>> replaceAvailability(@RequestBody List<BookingDTOs.Window> windows) {
        return ResponseEntity.ok(bookingService.replaceAvailability(windows));
    }
}
