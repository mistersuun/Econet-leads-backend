package com.econet.leads.exception;

import com.econet.leads.integration.ImportAlreadyRunningException;
import com.econet.leads.realtime.ClaimConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders every API error as {"error": "..."} (validation errors add "fields": {field: message}),
 * as required by the API contract.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 409 with who holds the claim: {error, claimedBy: {userId, username}, expiresAt}. */
    @ExceptionHandler(ClaimConflictException.class)
    public ResponseEntity<Object> handleClaimConflict(ClaimConflictException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("claimedBy", Map.of("userId", ex.getClaim().userId(), "username", ex.getClaim().username()));
        body.put("expiresAt", ex.getClaim().expiresAt());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** 409 with the existing lead: {error, duplicate: BusinessDTO}. */
    @ExceptionHandler(DuplicateLeadException.class)
    public ResponseEntity<Object> handleDuplicateLead(DuplicateLeadException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("duplicate", ex.getDuplicate());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException ex) {
        return error(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> handleResponseStatus(ResponseStatusException ex) {
        return error(ex.getStatusCode(), ex.getReason() != null ? ex.getReason() : ex.getMessage());
    }

    @ExceptionHandler(ImportAlreadyRunningException.class)
    public ResponseEntity<Object> handleImportRunning(ImportAlreadyRunningException ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgument(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "Valeur invalide pour le paramètre « " + ex.getName() + " »");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        return error(HttpStatus.FORBIDDEN, "Accès refusé");
    }

    @ExceptionHandler({DisabledException.class, LockedException.class})
    public ResponseEntity<Object> handleDisabled(AuthenticationException ex) {
        return error(HttpStatus.UNAUTHORIZED, "Compte désactivé");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex) {
        return error(HttpStatus.UNAUTHORIZED, "Nom d'utilisateur ou mot de passe invalide");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne du serveur, réessayez dans un instant");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Validation échouée");
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    /** All other Spring MVC exceptions (bad JSON, missing params, 404, 405, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             @Nullable Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        String message = ex.getMessage();
        if (ex instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            message = "Requête mal formée";
            Throwable cause = ex.getCause();
            if (cause instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException ife) {
                message = "Valeur invalide « " + ife.getValue() + " »"
                        + (ife.getPath().isEmpty() ? "" : " pour le champ « " + ife.getPath().get(ife.getPath().size() - 1).getFieldName() + " »");
            }
        }
        if (!(ex instanceof org.springframework.http.converter.HttpMessageNotReadableException)) {
            message = genericMessage(statusCode, message);
        }
        return ResponseEntity.status(statusCode).headers(headers).body(Map.of("error", message != null ? message : "Erreur dans la requête"));
    }

    private static ResponseEntity<Object> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message != null ? message : "Erreur"));
    }

    /** French fallback for framework exceptions whose own message is English. */
    private static String genericMessage(HttpStatusCode status, String original) {
        return switch (status.value()) {
            case 400 -> "Requête invalide";
            case 401 -> "Non autorisé";
            case 403 -> "Accès refusé";
            case 404 -> "Ressource introuvable";
            case 405 -> "Méthode non permise";
            case 409 -> "Conflit avec l'état actuel des données";
            case 413 -> "Fichier trop volumineux";
            case 415 -> "Type de contenu non pris en charge";
            case 429 -> "Trop de requêtes, réessayez dans un instant";
            case 500 -> "Erreur interne du serveur, réessayez dans un instant";
            default -> original;
        };
    }
}
