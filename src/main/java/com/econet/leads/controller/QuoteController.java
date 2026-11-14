package com.econet.leads.controller;

import com.econet.leads.dto.QuoteDTOs;
import com.econet.leads.model.User;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.QuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Quotes", description = "Quotes with PDF and a public accept link")
public class QuoteController {

    private final QuoteService quoteService;
    private final AuthenticationFacade authenticationFacade;

    @PostMapping("/leads/{id}/quotes")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Create a quote", description = "{ estimate: {service, area, propertyType, addOnIds, frequency}, language? } priced like the website; "
            + "400 when the area needs a custom quote. The quote starts as a draft with a public link.")
    public ResponseEntity<QuoteDTOs.Quote> create(@PathVariable UUID id, @Valid @RequestBody QuoteDTOs.CreateRequest request) {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.status(HttpStatus.CREATED).body(quoteService.create(id, request, user));
    }

    @GetMapping("/leads/{id}/quotes")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Quotes of a lead", description = "Newest first; status is expired once past validUntil")
    public ResponseEntity<List<QuoteDTOs.Quote>> list(@PathVariable UUID id) {
        return ResponseEntity.ok(quoteService.listForLead(id));
    }

    @GetMapping(value = "/quotes/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Quote PDF")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        QuoteService.PdfFile file = quoteService.pdf(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.bytes());
    }

    @PostMapping("/quotes/{id}/send")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Email the quote", description = "{ to? } PDF attached and accept link; 503 without SMTP; marks the quote sent, "
            + "logs an EMAIL contact and moves the lead to QUOTE_SENT if it is earlier in the pipeline")
    public ResponseEntity<QuoteDTOs.Quote> send(@PathVariable UUID id, @Valid @RequestBody(required = false) QuoteDTOs.SendRequest request) {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.ok(quoteService.send(id, request, user));
    }
}
