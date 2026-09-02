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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

/**
 * Errors this service raises leave as application/problem+json, and so do the
 * ones Spring raises before a handler runs. An unhandled exception is not
 * covered: it reaches the default error controller as application/json, which
 * the 28 August audit recorded and BI15 does not include. The status codes are chosen
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

    // Overrides rather than @ExceptionHandler methods: the parent already maps
    // both types, and two mappings for one type in one advice class is ambiguous.
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String fields = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .sorted().collect(Collectors.joining("; "));
        return validationFailed(e, fields, headers, status, request);
    }

    // A rejected @RequestParam arrives here, not above, and the inherited answer
    // is "Validation failure" with no parameter named. One service should not
    // have two shapes for the same 400.
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // A constrained return value fails with 500, and that is our defect, not
        // the caller's. The parent answers it opaquely, which is what a server
        // fault should say.
        if (e.isForReturnValue()) {
            return super.handleHandlerMethodValidationException(e, headers, status, request);
        }
        String params = e.getParameterValidationResults().stream()
            .flatMap(r -> r.getResolvableErrors().stream()
                .map(err -> parameterName(r.getMethodParameter()) + ": " + err.getDefaultMessage()))
            .sorted().collect(Collectors.joining("; "));
        return validationFailed(e, params, headers, status, request);
    }

    private ResponseEntity<Object> validationFailed(Exception e, String detail, HttpHeaders headers,
                                                    HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(e,
            problem(status, "Validation failed", detail.isEmpty() ? "invalid request" : detail),
            headers, status, request);
    }

    private static String parameterName(MethodParameter p) {
        String name = p.getParameterName();
        return name != null ? name : "parameter " + p.getParameterIndex();
    }

    private static ProblemDetail problem(HttpStatusCode status, String title, String detail) {
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
