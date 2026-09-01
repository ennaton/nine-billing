package co.nine.billing.api;

import co.nine.billing.auth.TenantMismatchException;
import co.nine.billing.domain.DuplicateEntryException;
import co.nine.billing.domain.UnbalancedEntryException;
import co.nine.billing.domain.UnknownCurrencyException;
import co.nine.billing.infrastructure.ConstraintRules;
import co.nine.billing.metering.UnknownMetricException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves as application/problem+json. The status codes are chosen
 * so a client can act without parsing the message: 409 means "already done or
 * conflicts", 422 means "your request is well formed but cannot be honored",
 * 400 means "fix the request".
 *
 * <p>Extending ResponseEntityExceptionHandler puts the twenty exceptions Spring
 * throws before a handler runs under the same contract. Without it they reached
 * the default error controller and left as application/json.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(UnknownMetricException.class)
    ProblemDetail unknownMetric(UnknownMetricException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown metric", e.getMessage());
    }

    // UnknownCurrencyException extends IllegalArgumentException, which is
    // handled below as 422. This one wins, and not because it is written first:
    // ExceptionHandlerMethodResolver ranks candidates with ExceptionDepthComparator,
    // so the subtype matches at depth 0 against the supertype's depth 1. Position
    // in this file carries no meaning, which is the stronger guarantee. Nobody can
    // break the distinction by reordering these methods.
    @ExceptionHandler(UnknownCurrencyException.class)
    ProblemDetail unknownCurrency(UnknownCurrencyException e) {
        return problem(HttpStatus.BAD_REQUEST, "Unknown currency", e.getMessage());
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
        // Which one is decided by the SQLState and constraint name the server
        // reports, in ConstraintRules, and never by reading the message text.
        // The message is formatting: it changes with the server version, it
        // names an index the client has no business knowing, and keying on it
        // makes a rename a breaking change.
        String detail = ConstraintRules.of(e)
            .map(ConstraintRules.Rule::detail)
            .orElse("the write was refused by the ledger");
        return problem(HttpStatus.CONFLICT, "Ledger refused the operation", detail);
    }

    @ExceptionHandler({EmptyResultDataAccessException.class, TenantMismatchException.class})
    ProblemDetail notFound(RuntimeException e) {
        // A tenant asking about another tenant's data gets the same answer as
        // asking about nothing: 404. Existence is not disclosed.
        return problem(HttpStatus.NOT_FOUND, "Not found", "no such resource for this tenant");
    }

    // An override rather than an @ExceptionHandler: the parent already maps this
    // exception, and two mappings for one type in one advice class is ambiguous.
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String fields = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b).orElse("invalid request");
        return handleExceptionInternal(e, problem(HttpStatus.BAD_REQUEST, "Validation failed", fields),
            headers, HttpStatus.BAD_REQUEST, request);
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
