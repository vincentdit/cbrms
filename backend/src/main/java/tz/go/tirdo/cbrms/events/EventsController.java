package tz.go.tirdo.cbrms.events;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.go.tirdo.cbrms.common.ApiException;
import tz.go.tirdo.cbrms.config.CbrmsProperties;
import tz.go.tirdo.cbrms.identity.Actor;
import tz.go.tirdo.cbrms.identity.Actors;

/**
 * GET /internal/v1/events?after={cursor} lets a platform recover events it missed (FR25).
 * A short safety window hides very recent rows so that a late-committing transaction cannot be skipped.
 */
@RestController
@RequestMapping("/internal/v1/events")
public class EventsController {
    private record Row(long position, String payload) {}

    private final JdbcClient jdbc;
    private final Actors actors;
    private final CbrmsProperties props;

    public EventsController(JdbcClient jdbc, Actors actors, CbrmsProperties props) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.props = props;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> events(@RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "100") int limit) {
        Actor actor = actors.current();
        if (!actor.isPlatform()) {
            throw ApiException.forbidden("CBR-AUT-004", "Only platform clients can read the event feed");
        }
        int bounded = Math.max(1, Math.min(limit, 500));
        List<Row> rows = jdbc.sql("""
                SELECT position, payload::text FROM outbox_event
                 WHERE source_system_id = :sourceSystemId AND position > :after
                   AND created_at < now() - CAST(:window AS interval)
                 ORDER BY position
                 LIMIT :limit
                """)
            .param("sourceSystemId", actor.sourceSystemId())
            .param("after", after)
            .param("window", props.events().cursorSafetyWindow().toMillis() + " milliseconds")
            .param("limit", bounded)
            .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2)))
            .list();

        StringBuilder body = new StringBuilder("{\"events\":[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"position\":").append(rows.get(i).position()).append(",\"event\":").append(rows.get(i).payload()).append('}');
        }
        long next = rows.isEmpty() ? after : rows.get(rows.size() - 1).position();
        body.append("],\"nextCursor\":").append(next).append('}');
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body.toString());
    }
}
