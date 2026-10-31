package tz.go.tirdo.cbrms.gepg;

/** Outcome of an inbound GePG message. Every outcome except REJECTED_SIGNATURE is acknowledged to GePG. */
public enum InboundResult { ACCEPTED, DUPLICATE, SUSPENSE, IGNORED, REJECTED_SIGNATURE }
