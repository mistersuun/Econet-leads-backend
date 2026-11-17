package com.econet.leads.controller;

import com.econet.leads.exception.ApiException;
import com.econet.leads.service.BookingService;
import com.econet.leads.service.PublicPageRenderer;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Unauthenticated visit booking page (single-use secret link); same guard as the quote pages. */
@RestController
@RequestMapping("/public/book")
@RequiredArgsConstructor
@Hidden
public class PublicBookingController {

    private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);

    private final BookingService bookingService;
    private final PublicPageRenderer pages;

    @GetMapping(value = "/{token}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> page(@PathVariable String token) {
        return html(HttpStatus.OK, pages.bookingPage(bookingService.view(token), null));
    }

    /** Browser form: books and redirects back to the page (which then shows the confirmation). */
    @PostMapping(value = "/{token}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> bookForm(@PathVariable String token, @RequestParam(required = false) String slot) {
        try {
            bookingService.book(token, slot);
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/public/book/" + token)).build();
        } catch (BookingService.SlotUnavailableException e) {
            BookingService.PublicView view = bookingService.view(token);
            boolean en = "en".equals(view.language());
            return html(HttpStatus.CONFLICT, pages.bookingPage(view, en
                    ? "This time was just taken. Please pick another one."
                    : "Cette plage vient d'être prise. Veuillez en choisir une autre."));
        } catch (BookingService.LinkClosedException e) {
            return html(HttpStatus.CONFLICT, pages.bookingPage(bookingService.view(token), null));
        }
    }

    /** JSON variant: { "slot": "2026-11-20T10:00" } -> { startsAt, endsAt }. */
    @PostMapping(value = "/{token}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> bookJson(@PathVariable String token, @RequestBody Map<String, String> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            BookingService.Booked booked = bookingService.book(token, body == null ? null : body.get("slot"));
            out.put("startsAt", booked.startsAt());
            out.put("endsAt", booked.endsAt());
            return ResponseEntity.status(HttpStatus.CREATED).body(out);
        } catch (BookingService.SlotUnavailableException e) {
            out.put("error", "slot unavailable");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
        } catch (BookingService.LinkClosedException e) {
            out.put("error", "link " + e.state().name().toLowerCase());
            return ResponseEntity.status(HttpStatus.GONE).body(out);
        }
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> apiError(ApiException e) {
        HttpStatus status = e.getStatus();
        String body = status == HttpStatus.NOT_FOUND ? pages.notFoundPage()
                : pages.messagePage(false, "Erreur", "La requête n'a pas pu être traitée.");
        return html(status, body);
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(HTML).body(body);
    }
}
