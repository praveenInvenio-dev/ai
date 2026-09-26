package com.aistorystudio.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> notFound(IllegalArgumentException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> security(SecurityException e) {
        return build(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        return build(HttpStatus.BAD_REQUEST, "Validation failed: " + e.getMessage());
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void clientDisconnected(org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
        // The client (browser) closed the connection mid-stream - normal when a
        // <video>/<img>/<audio> tag's request gets cancelled by navigation, a range
        // request retry, or the asset not being ready yet. Nothing to send back:
        // the response is already broken, and trying to write a JSON error body
        // onto it just produces a second, noisier exception on top of this one.
        log.debug("Client disconnected mid-response: {}", e.getMessage());
    }

    /**
     * Same situation as above but for the SSE progress stream specifically.
     *
     * The socket write for an SseEmitter happens on Tomcat's own I/O thread,
     * separately from whatever thread called emitter.send() - so a client that
     * closed its EventSource (tab closed, navigated away, reconnected) surfaces
     * here as a plain IOException ("Broken pipe"), not the async-specific type
     * above, and JobEventService's own try/catch around send() never sees it.
     * Routing it to the generic handler tried to write a JSON body onto a
     * `text/event-stream` response, which itself threw
     * HttpMessageNotWritableException on top of the original error.
     */
    @ExceptionHandler(java.io.IOException.class)
    public void clientDisconnectedStream(java.io.IOException e) {
        log.debug("Client disconnected from stream: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        log.error("Unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error: " + e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
