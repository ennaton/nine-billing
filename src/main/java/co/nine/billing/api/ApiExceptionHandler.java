package co.nine.billing.api;

import co.nine.billing.auth.TenantMismatchException;
import co.nine.billing.domain.DuplicateEntryException;
import co.nine.billing.domain.UnbalancedEntryException;
import co.nine.billing.metering.UnknownMetricException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every error leaves as application/problem+json. The status codes are chosen
 * so a client can act without parsing the message: 409 means "already done or
 * conflicts", 422 means "your request is well formed but cannot be honored",
 * 400 means "fix the request".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnknownMetricException.class)
    ProblemDetail unknownMetric(UnknownMetricException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown metric", e.getMessage());
    }

    @ExceptionHandler({UnbalancedEntryException.class, IllegalArgumentException.class})
    ProblemDetail badEntry(RuntimeException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Entry rejected", e.getMessage());
    }

    @ExceptionHandler(DuplicateEntryException.class)
    ProblemDetail duplicate(DuplicateEntryException e) {
        return problem(HttpStatus.CONFLICT, "Duplicate entry", e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        // The ledger said no: already reversed, currency mismatch, immutability.
        String detail = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        return problem(HttpStatus.CONFLICT, "Ledger refused the operation", firstLine(detail));
    }

    @ExceptionHandler({EmptyResultDataAccessException.class, TenantMismatchException.class})
    ProblemDetail notFound(RuntimeException e) {
        // A tenant asking about another tenant's data gets the same answer as
        // asking about nothing: 404. Existence is not disclosed.
        return problem(HttpStatus.NOT_FOUND, "Not found", "no such resource for this tenant");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b).orElse("invalid request");
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", fields);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return p;
    }

    private static String firstLine(String s) {
        if (s == null) return null;
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
