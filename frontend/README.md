# CBRMS Finance console

Angular 22 console for TIRDO Finance staff:
- **Today:** what needs attention and today's collections.
- **Bills:** search and filter every bill, including by platform (LIMS, CIAP, TeLTP, ISICMS).
- **Bill detail:** control number, progress, charges, payments, history sent to the platform and exceptions.
- **Bill detail** also has officer actions: cancel the bill, give a new deadline and refund client credit. It also shows change requests, credit and messages to the client.
- **Approvals:** one queue for new bills, cancellations, new deadlines and refunds, filterable by kind.
- **Exceptions:** cases CBRMS could not settle on its own.
- **Reconciliation:** daily runs, line-by-line results, run now, and sign-off.
- **Refunds:** approved refunds to pay out; record the bank reference or a failure.
- **Client messages:** SMS and email sent to clients, with recipients masked.
- **Check a bill** (`/check`, public, no sign-in): control number plus SMS one-time code.
- **GePG simulator:** local and test environments only. It can also add lines to the GePG reconciliation file.

## Run

The whole stack runs from `C:\dev\cbrms` (see the README there). The console is served by nginx on http://localhost:8086 and proxies `/internal`, `/public` and `/sim` to the backend, so the browser never needs CORS.

For live editing with hot reload, start the backend in Docker first, then run:

```powershell
cd C:\dev\cbrms\frontend; npm install; npx ng serve --open
```

`ng serve` proxies API calls to http://localhost:8085 (see `proxy.conf.json`).

## Settings

`public/config.json` is read at start-up, so one build can be deployed anywhere:

| Key | Meaning |
|---|---|
| `simulator` | Show the GePG simulator page. Set `false` in production. |
| `authMode` | `oidc` for Keycloak single sign-on (authorization code with PKCE; tokens kept in memory and refreshed before each call), or `basic` for the development users. |
| `oidc` | `{ "url", "realm", "clientId" }` for Keycloak. Required when `authMode` is `oidc`. |
| `environmentLabel` | Shown in the navigation so staff can tell test from live. |

`config.sso.json` is the single sign-on version; `docker-compose.sso.yml` mounts it in place of `config.json`.

`nginx.conf` is a template. At start-up, nginx fills `${CBRMS_AUTH_ORIGIN}` (the Keycloak address) into the Content-Security-Policy, so the browser may call Keycloak and nothing else.

## Conventions

- Amounts arrive from the API as decimal strings and are only formatted, never calculated.
- Dates and times are shown in Africa/Dar_es_Salaam time.
- States are always shown as words with a mark. Colour is never the only signal.
- Keyboard focus is always visible, and there is a "Skip to content" link.
