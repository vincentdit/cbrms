package tz.go.tirdo.cbrms.gepg;

/** Synchronous acknowledgement of a submission. The control number arrives later as a separate message. */
public record SubmitResult(boolean acknowledged, String responseCode, String responseDigest) {}
