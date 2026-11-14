# Security headers for every console response. nginx drops server-level add_header lines in any location that
# has its own add_header, so this file is included in each such location as well as at server level.
# Template: ${CBRMS_AUTH_ORIGIN} is filled at start-up (the Keycloak origin, or empty without single sign-on).
add_header X-Content-Type-Options nosniff always;
add_header X-Frame-Options DENY always;
add_header Referrer-Policy same-origin always;
add_header Cross-Origin-Opener-Policy same-origin always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;
# Browsers ignore this over plain HTTP; once the console is served over HTTPS it keeps them on HTTPS for a year.
add_header Strict-Transport-Security "max-age=31536000" always;
add_header Content-Security-Policy "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self'; connect-src 'self' ${CBRMS_AUTH_ORIGIN}; frame-ancestors 'none'; base-uri 'self'; form-action 'self' ${CBRMS_AUTH_ORIGIN}; object-src 'none'" always;
