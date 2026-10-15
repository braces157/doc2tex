package dev.doc2tex;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@RestControllerAdvice
class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> known(ApiException exception) {
        return ResponseEntity.status(exception.status).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() {
        return ResponseEntity.status(413).body(Map.of("message", "Choose a DOCX file smaller than 20 MB."));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalidRequest() {
        return ResponseEntity.badRequest().body(Map.of("message", "The request is invalid. Check the file and options."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception exception) {
        log.error("Request failed", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Something went wrong while processing this request. Check the application log."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> missingResource() {
        return ResponseEntity.status(404).body(Map.of("message", "This resource could not be found."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> unsupportedMethod() {
        return ResponseEntity.status(405).body(Map.of("message", "This HTTP method is not supported for this resource."));
    }
}
