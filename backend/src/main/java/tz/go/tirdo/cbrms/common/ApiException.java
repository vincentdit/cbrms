package tz.go.tirdo.cbrms.common;

import java.util.List;

/** Business error with a stable internal code (SRS section 6 error catalogue). */
public class ApiException extends RuntimeException {

    public record FieldProblem(String field, String message) {}

    private final int status;
    private final String code;
    private final List<FieldProblem> fieldProblems;

    public ApiException(int status, String code, String message, List<FieldProblem> fieldProblems) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldProblems = fieldProblems == null ? List.of() : List.copyOf(fieldProblems);
    }

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public int status() { return status; }
    public String code() { return code; }
    public List<FieldProblem> fieldProblems() { return fieldProblems; }

    public static ApiException validation(String message, List<FieldProblem> problems) {
        return new ApiException(422, "CBR-VAL-001", message, problems);
    }

    public static ApiException validation(String field, String message) {
        return validation("Validation failed", List.of(new FieldProblem(field, message)));
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(403, code, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "CBR-NFD-001", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }
}
