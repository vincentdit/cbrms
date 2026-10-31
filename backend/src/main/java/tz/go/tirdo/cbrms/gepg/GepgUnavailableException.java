package tz.go.tirdo.cbrms.gepg;

/** Timeout or transport failure: GePG may or may not have received the bill. */
public class GepgUnavailableException extends Exception {
    public GepgUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
