# Fonja

Crowdfunding platform backend — modular monolith (Spring Boot / MySQL / Docker).

## Module boundaries

Each package under `com.fonja.*` owns its own tables and is only called through
its `service` layer — never reach into another module's `repository` or
`entity` classes directly.

| Module | Owns | Status |
|---|---|---|
| `user` | Accounts, OAuth login, roles | Built |
| `verification` | Creator verification gate, triggered at campaign Launch | Built |
| `campaign` | Draft-through-Active lifecycle, feed | Schema only (Day 2) |
| `donation` | CyberPay donation integration | Schema only (Day 3) |
| `ledger` | Append-only earnings records (no user-facing wallet) | Schema only (Day 2) |
| `payout` | Withdrawal requests | Schema only (Day 3) |
| `notification` | Push / email / SMS | Schema only |
| `common` | Shared config, security, response wrapper | Built |

All seven modules' tables exist from V1–V7 because `ddl-auto: validate`
validates the whole entity graph at boot — a missing table for any entity stops
the app starting, even one whose behaviour is not written yet.

## Money-path rules (non-negotiable — see technical spec)

1. All CyberPay callbacks are idempotent on `gateway_reference`.
2. `LedgerEntry` is append-only — no updates, ever. Balances are derived, not stored.
3. Donating requires only a registered account. Creating/publishing a campaign
   and withdrawing require verification — verification happens once, at Launch,
   not per transaction.

## Auth

Google OAuth2 is the Day 1 provider. The handshake runs once; from then on
every `/api/**` call carries an internal HS256 JWT whose subject is the Fonja
`user_id`, not the provider's `sub`.

Apple is deliberately not registered yet: its client secret is itself a JWT
that expires within six months, so adding it means adding secret generation and
rotation rather than pasting a credential.

Two security filter chains, on purpose: `/api/**` is stateless and
bearer-token-only, while the OAuth2 handshake endpoints keep a session to hold
the authorization request across the redirect.

## Storage

KYC/evidence documents go through `verification.service.StorageService`.
Current implementation is local filesystem (`LocalStorageServiceImpl`) — this
is explicitly a placeholder. Do not deploy to more than one app instance until
this is migrated to Azure Blob Storage.

## Getting started

```
cp .env.example .env          # then fill in FONJA_AUTH_JWT_SECRET (openssl rand -base64 48)
docker compose up -d mysql    # MySQL only; set MYSQL_HOST_PORT if 3306 is taken
./mvnw spring-boot:run
```

Or run everything in containers with `docker compose up -d`.

Google sign-in needs a real client from the Google Cloud console with
`http://localhost:8080/login/oauth2/code/google` as an authorised redirect URI.
Without one the app still starts and `/api/**` still validates tokens; only the
`/oauth2/authorization/google` handshake is unusable.

## Toolchain notes

Spring Boot 4 split autoconfiguration into per-technology modules. `flyway-core`
alone gives you no autoconfiguration and migrations silently never run — the
`spring-boot-flyway` dependency is what wires them in. Boot 4 also autoconfigures
Jackson 3 (`tools.jackson.databind.ObjectMapper`); Jackson 2 is present only
transitively and has no bean.
