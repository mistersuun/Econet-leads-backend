package com.econet.leads.controller;

import com.econet.leads.exception.ApiException;
import com.econet.leads.model.Quote;
import com.econet.leads.security.ClientIp;
import com.econet.leads.service.PublicPageRenderer;
import com.econet.leads.service.QuoteService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * Unauthenticated quote pages. The secret token in the URL is the credential; see
 * {@link com.econet.leads.security.PublicPagesFilter} for headers, rate limit and the Origin check.
 */
@RestController
@RequestMapping("/public/quotes")
@RequiredArgsConstructor
@Hidden
public class PublicQuoteController {

    private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);

    private final QuoteService quoteService;
    private final PublicPageRenderer pages;

    @GetMapping(value = "/{token}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> page(@PathVariable String token) {
        return html(HttpStatus.OK, pages.quotePage(quoteService.findPublic(token), null));
    }

    @GetMapping(value = "/{token}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String token) {
        byte[] bytes = quoteService.publicPdf(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("soumission.pdf").build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    @PostMapping(value = "/{token}/accept", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> accept(@PathVariable String token,
                                         @RequestParam(required = false) String fullName,
                                         @RequestParam(required = false) String agree,
                                         HttpServletRequest request) {
        QuoteService.PublicResult result = quoteService.accept(token, fullName, agree != null && !agree.isBlank(), ClientIp.of(request));
        if (result == QuoteService.PublicResult.INVALID_INPUT) {
            Quote quote = quoteService.findPublic(token);
            boolean en = "en".equals(quote.getLanguage());
            return html(HttpStatus.BAD_REQUEST, pages.quotePage(quote, en
                    ? "Please enter your full name and tick the box to accept."
                    : "Veuillez saisir votre nom complet et cocher la case pour accepter."));
        }
        return back(token);
    }

    @PostMapping(value = "/{token}/decline", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> decline(@PathVariable String token, @RequestParam(required = false) String reason) {
        quoteService.decline(token, reason);
        return back(token);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> notFound(ApiException e) {
        HttpStatus status = e.getStatus();
        String body = status == HttpStatus.NOT_FOUND ? pages.notFoundPage()
                : pages.messagePage(false, "Erreur", "La requête n'a pas pu être traitée.");
        return html(status, body);
    }

    private static ResponseEntity<String> back(String token) {
        // Post/redirect/get: the page shows the new state and a refresh never re-posts. The token was validated by the call above.
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/public/quotes/" + token)).build();
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(HTML).body(body);
    }
}
