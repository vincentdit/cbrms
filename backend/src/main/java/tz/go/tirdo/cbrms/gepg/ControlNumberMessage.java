package tz.go.tirdo.cbrms.gepg;

/** Normalised control-number result, whatever the wire format of the assigned contract. */
public record ControlNumberMessage(String messageId, String gepgBillId, boolean accepted, String controlNumber, String responseCode) {}
