package tz.go.tirdo.cbrms.domain;

import java.time.Duration;
import java.util.random.RandomGenerator;

/** Exponential backoff with jitter for event delivery retries (FR24). */
public record Backoff(Duration base, Duration max, double jitter) {

    public Backoff {
        if (jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("jitter must be in [0,1)");
        }
    }

    public Duration delay(int attempt, RandomGenerator random) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt starts at 1");
        }
        long baseMs = base.toMillis();
        long capped = Math.min(max.toMillis(), baseMs * (1L << Math.min(attempt - 1, 30)));
        double factor = 1 + jitter * (random.nextDouble() * 2 - 1);
        return Duration.ofMillis(Math.max(1, Math.round(capped * factor)));
    }
}
