package tz.go.tirdo.cbrms.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Workers run on a schedule unless cbrms.scheduling.enabled=false (integration tests drive them directly). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "cbrms.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
