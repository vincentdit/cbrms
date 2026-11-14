package tz.go.tirdo.cbrms.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * cbrms.security.mode selects how callers sign in:
 * <ul>
 *   <li><b>jwt</b> (production): Keycloak-issued tokens. Staff use single sign-on with MFA; platforms use client credentials.
 *       Tokens must carry the CBRMS audience (spring.security.oauth2.resourceserver.jwt.audiences), so a token issued
 *       for another TIRDO application in the same realm is refused.</li>
 *   <li>cbrms.security.staff-acr (jwt mode): when set (production: "mfa"), staff roles in a token count only if its
 *       "acr" claim is one of the listed levels, so a staff token from a password-only sign-in can read and change
 *       nothing. Platform and technical-operator (service account) roles are not affected.</li>
 *   <li><b>basic</b> (local and test only): fixed development users. Refuses to start unless the GePG simulator is on,
 *       so it cannot be enabled by mistake against real GePG.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    static final String[] PLATFORM_OR_FINANCE = {"PLATFORM", "BILLING_OFFICER", "BILL_APPROVER", "FINANCE_VIEWER", "AUDITOR"};
    /** Roles that may change anything. FINANCE_VIEWER and AUDITOR only read. */
    static final String[] ACTORS = {"PLATFORM", "BILLING_OFFICER", "BILL_APPROVER"};
    /** People, as opposed to systems: these roles need the stronger sign-in when staff-acr is set. */
    static final Set<String> STAFF_ROLES = Set.of("BILLING_OFFICER", "BILL_APPROVER", "FINANCE_VIEWER", "AUDITOR");

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    @ConditionalOnProperty(name = "cbrms.security.mode", havingValue = "basic")
    SecurityFilterChain basicChain(HttpSecurity http, @Value("${cbrms.gepg.mode}") String gepgMode) throws Exception {
        if (!"simulator".equals(gepgMode)) {
            throw new IllegalStateException("cbrms.security.mode=basic is only allowed with the GePG simulator (development and test)");
        }
        rules(http, true);
        // 401 without "WWW-Authenticate: Basic", so browsers do not open their own sign-in dialog over the console
        http.httpBasic(b -> b.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        http.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "cbrms.security.mode", havingValue = "basic")
    UserDetailsService developmentUsers() {
        return new InMemoryUserDetailsManager(
                User.withUsername("lims").password("{noop}lims-dev-secret").roles("PLATFORM").build(),
                User.withUsername("ciap").password("{noop}ciap-dev-secret").roles("PLATFORM").build(),
                User.withUsername("teltp").password("{noop}teltp-dev-secret").roles("PLATFORM").build(),
                User.withUsername("isicms").password("{noop}isicms-dev-secret").roles("PLATFORM").build(),
                User.withUsername("finance.maker").password("{noop}maker-dev").roles("BILLING_OFFICER").build(),
                User.withUsername("finance.approver").password("{noop}approver-dev").roles("BILL_APPROVER").build(),
                User.withUsername("internal.auditor").password("{noop}auditor-dev").roles("AUDITOR").build(),
                // Prometheus scrapes /actuator/prometheus as this user in basic mode
                User.withUsername("monitoring").password("{noop}monitoring-dev").roles("TECHNICAL_OPERATOR").build());
    }

    @Bean
    @ConditionalOnProperty(name = "cbrms.security.mode", havingValue = "jwt", matchIfMissing = true)
    SecurityFilterChain jwtChain(HttpSecurity http, @Value("${cbrms.gepg.mode}") String gepgMode,
                                 @Value("${cbrms.security.staff-acr:}") String staffAcr) throws Exception {
        rules(http, "simulator".equals(gepgMode));
        Set<String> levels = parseLevels(staffAcr);
        log.info(levels.isEmpty() ? "Staff sign-in level not enforced (cbrms.security.staff-acr is empty)"
                : "Staff roles require sign-in level (acr) in " + levels);
        http.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(keycloakConverter(levels))));
        return http.build();
    }

    private static void rules(HttpSecurity http, boolean simulatorOpen) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> {
                a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                // GePG callbacks are authenticated by message signature inside the adapter (FR12)
                a.requestMatchers("/gepg/**").permitAll();
                // Client bill lookup is protected by one-time codes and rate limits (FR32)
                a.requestMatchers("/public/v1/**").permitAll();
                if (simulatorOpen) {
                    a.requestMatchers("/sim/**").permitAll();
                }
                a.requestMatchers("/internal/v1/bills/*/approval", "/internal/v1/change-requests/*/decision",
                        "/internal/v1/refunds/*/decision", "/internal/v1/quotations/*/decision",
                        "/internal/v1/catalogue-changes/*/decision", "/internal/v1/corrections/*/decision").hasRole("BILL_APPROVER");
                a.requestMatchers("/internal/v1/events", "/internal/v1/events/**").hasRole("PLATFORM");
                // FINANCE_VIEWER is read-only: every Finance action is a POST under /admin
                a.requestMatchers(HttpMethod.POST, "/internal/v1/admin/**").hasAnyRole("BILLING_OFFICER", "BILL_APPROVER");
                a.requestMatchers("/internal/v1/admin/**").hasAnyRole("BILLING_OFFICER", "BILL_APPROVER", "FINANCE_VIEWER", "AUDITOR");
                // Read-only roles cannot change anything elsewhere either (for example request a cancellation)
                a.requestMatchers(HttpMethod.POST, "/internal/**").hasAnyRole(ACTORS);
                a.requestMatchers("/internal/**").hasAnyRole(PLATFORM_OR_FINANCE);
                a.requestMatchers("/actuator/**").hasRole("TECHNICAL_OPERATOR");
                a.anyRequest().denyAll();
            });
    }

    static Set<String> parseLevels(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(v -> !v.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Maps Keycloak realm roles (realm_access.roles) to ROLE_* authorities; principal is preferred_username.
     * With staffAcr non-empty, staff roles are dropped from tokens whose "acr" is not one of those levels.
     */
    static JwtAuthenticationConverter keycloakConverter(Set<String> staffAcr) {
        Converter<Jwt, Collection<GrantedAuthority>> roles = jwt -> {
            Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
            Object list = realm == null ? null : realm.get("roles");
            if (!(list instanceof Collection<?> names)) {
                return List.of();
            }
            String acr = jwt.getClaimAsString("acr");   // may be absent; Set.of(..).contains(null) would throw
            boolean strongSignIn = staffAcr.isEmpty() || (acr != null && staffAcr.contains(acr));
            List<GrantedAuthority> granted = new java.util.ArrayList<>();
            for (Object n : names) {
                String role = String.valueOf(n).toUpperCase(Locale.ROOT);
                if (!strongSignIn && STAFF_ROLES.contains(role)) {
                    log.warn("Staff role {} ignored for {}: sign-in level (acr) {} is not one of {}",
                            role, jwt.getClaimAsString("preferred_username"), acr, staffAcr);
                    continue;
                }
                granted.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            return granted;
        };
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
}
