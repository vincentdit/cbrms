package tz.go.tirdo.cbrms.common;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(HttpStatusCode.valueOf(e.status()))
                .body(new ApiError(e.code(), e.getMessage(), Correlation.current(), e.fieldProblems()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        List<ApiException.FieldProblem> problems = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiException.FieldProblem(f.getField(), f.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatusCode.valueOf(422))
                .body(new ApiError("CBR-VAL-001", "Validation failed", Correlation.current(), problems));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatusCode.valueOf(422))
                .body(new ApiError("CBR-VAL-002", "Request body is not valid JSON for this operation", Correlation.current(), List.of()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.status(HttpStatusCode.valueOf(422))
                .body(new ApiError("CBR-VAL-003", "Missing required header " + e.getHeaderName(), Correlation.current(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        if (e instanceof ErrorResponse framework) {
            // Spring MVC errors such as 404, 405 and 415 keep their own status
            return ResponseEntity.status(framework.getStatusCode())
                    .body(new ApiError("CBR-HTTP-" + framework.getStatusCode().value(), "Request not supported", Correlation.current(), List.of()));
        }
        log.error("Unhandled error [{}]", Correlation.current(), e);
        return ResponseEntity.status(HttpStatusCode.valueOf(500))
                .body(new ApiError("CBR-SYS-001", "Unexpected error. Quote the correlation ID to support.", Correlation.current(), List.of()));
    }
}
