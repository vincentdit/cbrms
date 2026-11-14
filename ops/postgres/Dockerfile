# PostgreSQL 18 with pgBackRest and the CBRMS backup and restore-drill scripts (NFR05).
FROM postgres:18
RUN apt-get update \
 && apt-get install -y --no-install-recommends pgbackrest \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /var/lib/pgbackrest /var/log/pgbackrest /etc/pgbackrest \
 && chown -R postgres:postgres /var/lib/pgbackrest /var/log/pgbackrest
COPY pgbackrest.conf /etc/pgbackrest/pgbackrest.conf
COPY cbrms-backup cbrms-restore-drill /usr/local/bin/
# Scripts edited on Windows may have CRLF line endings; bash needs LF
RUN sed -i 's/\r$//' /usr/local/bin/cbrms-backup /usr/local/bin/cbrms-restore-drill /etc/pgbackrest/pgbackrest.conf \
 && chmod 755 /usr/local/bin/cbrms-backup /usr/local/bin/cbrms-restore-drill \
 && chmod 644 /etc/pgbackrest/pgbackrest.conf
