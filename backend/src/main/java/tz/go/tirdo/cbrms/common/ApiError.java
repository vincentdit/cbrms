package tz.go.tirdo.cbrms.common;

import java.util.List;

/** Error body: safe message, internal code and correlation ID; never stack traces or secrets. */
public record ApiError(String code, String message, String correlationId, List<ApiException.FieldProblem> fieldErrors) {}
