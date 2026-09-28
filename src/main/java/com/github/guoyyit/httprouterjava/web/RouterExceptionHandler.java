package com.github.guoyyit.httprouterjava.web;

import com.github.guoyyit.httprouterjava.core.ConflictException;
import com.github.guoyyit.httprouterjava.core.InvalidRouteException;
import com.github.guoyyit.httprouterjava.core.MethodNotAllowedException;
import com.github.guoyyit.httprouterjava.core.NoMatchException;
import com.github.guoyyit.httprouterjava.core.PersistenceException;
import com.github.guoyyit.httprouterjava.core.RouterException;
import com.github.guoyyit.httprouterjava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Maps the distinguishable router failure types to distinct HTTP status codes
 * and machine-readable error codes. Nothing is silently swallowed.
 */
@RestControllerAdvice
public class RouterExceptionHandler {

    @ExceptionHandler(NoMatchException.class)
    public ResponseEntity<ErrorResponse> noMatch(NoMatchException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NO_MATCH", e.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodNotAllowedException.class)
    public ResponseEntity<ErrorResponse> methodNotAllowed(MethodNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse("METHOD_NOT_ALLOWED", e.getMessage(),
                        e.getAllowedMethods()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFLICT", e.getMessage(),
                        e.getConflictingHandlers()));
    }

    @ExceptionHandler(InvalidRouteException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidRouteException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_ROUTE", e.getMessage(), List.of()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", e.getMessage(), List.of()));
    }

    @ExceptionHandler(PersistenceException.class)
    public ResponseEntity<ErrorResponse> persistence(PersistenceException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("PERSISTENCE_ERROR", e.getMessage(), List.of()));
    }

    @ExceptionHandler(RouterException.class)
    public ResponseEntity<ErrorResponse> other(RouterException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("ROUTER_ERROR", e.getMessage(), List.of()));
    }
}
