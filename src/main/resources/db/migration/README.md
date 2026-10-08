# Flyway migrations — the two version gaps, and why they are gaps

`spring.flyway.out-of-order=false` (`application.properties`). A migration may only be
applied if its version is above everything already applied. That makes the two holes in
this directory's numbering permanent facts rather than things to be filled in later, so
both are recorded here.

**Never reuse version 5 or version 8.** Dropping a new migration into either number would
either be skipped silently or apply in an ordering no environment has ever executed.

## V5 — never existed

There is **no** `flyway_schema_history` row for version 5 in any environment, including
production (verified 2026-10-08 against the read-only restore: ranks 1–8 are baseline, V1,
V2, V3, V4, V6, V7, V8 — there is no V5). So V5 was never applied anywhere. It was either
never written, or written and discarded before it was ever run.

This matters less than it looks: because it was never applied, no environment is missing
anything on account of it. Nothing needs recovering. `design-crosscutting.md:598` asks only
that the decision be recorded, which this is: **V5 is retired unused, and the number is
burned.**

## V8 — applied to production, never committed, now reconstructed

`V8__Shop_location_config.sql` was applied to production on 2025-12-07 20:19:35
(`flyway_schema_history` rank 8, checksum `1287378492`) and never committed. Searches of
every commit on every branch, every reachable and dangling git object, and the developer's
filesystem found nothing. The original file is unrecoverable.

`V8__Shop_location_config.sql` in this directory is a **reconstruction** of its schema
effect, built from the production dump's DDL. Its own header explains what it reproduces
and what it deliberately leaves out. See `design-domain.md` §6.2 and
`design-crosscutting.md:596-598` (risk R2).

### One-off `repair` needed on EVERY database that already has the original V8

Not just production — **the developer database too**, because it is a restore that already
carries the original V8 history row. Verified against it on 2026-10-08 with the read-only
`validate` goal:

```
Migration checksum mismatch for migration version 8
  -> Applied to database : 1287378492
  -> Resolved locally    : -553486591
Detected resolved migration not applied to database: 9, 10, 11, 12
```

Flyway runs `validate` before `migrate` by default, so until this is repaired **the
application will not start** against such a database — the new migrations never get a chance
to apply. Repair once per database:

For the **developer** database, copy-pasteable as-is:

```sh
mvn org.flywaydb:flyway-maven-plugin:repair \
  -Dflyway.url="jdbc:mysql://localhost:3306/recharge" \
  -Dflyway.user=CISADM \
  -Dflyway.password=cisadm \
  -Dflyway.locations=filesystem:src/main/resources/db/migration
```

For **production**, the same command with that environment's real host and credentials
substituted for all three values. `<host>`-style placeholders are not valid JDBC — pasting
them unedited fails with "Communications link failure", because the driver really does try
to resolve a host named `<host>`.

`repair` rewrites the version-8 row's checksum to match the file. It does **not** re-run the
migration and does **not** touch the schema or any data.

Confirm it worked with the read-only goal before starting the app — swap `repair` for
`validate`. A clean run reports only the four pending migrations.

Two details of that command are deliberate. It is **fully qualified** rather than
`mvn flyway:repair`: the plugin is declared in `pom.xml` pinned to `${flyway.version}`, the
same Flyway the application runs, and an unpinned prefix resolves to whatever the latest
release happens to be — a repair written by a different major version can record a checksum
the app then rejects, turning a one-off fix into a loop. And `flyway.locations` is passed
explicitly because the goal runs outside the Spring context, so it does not read
`spring.flyway.locations`.

### The local secrets file is sourced, not executed

`config/local-env.sh` (added by the secret-externalisation change) is `chmod 600` — readable,
deliberately **not** executable, because it holds a signing key and a device credential.
Running it as a script gives `permission denied`. Load it into the current shell instead:

```sh
. config/local-env.sh          # or: source config/local-env.sh
```

It is only needed once the externalisation has landed, because that is what replaces the
literal values with `${...}` placeholders that have no defaults. Before then the properties
files still carry their own values and the app starts without it.

### Where the migrations will actually apply

`application-local.properties` points at `jdbc:mysql://localhost:3306/recharge`, and
`application.properties` sets `spring.flyway.enabled=true`. That database is the developer
restore — a copy of production with real data, around 73,469 `rc_txn_header` rows. So
**starting the backend on the local profile applies V9–V12 to it.**

That is the right place to exercise this slice: `implementation-plan.md:69` asks for exactly
"a prod copy". Just know it is deliberate rather than incidental, and that V9 rebuilds four
live tables under an exclusive lock on the way through (see V9's header), so run it when
nothing else is using that database.

## A blank environment still cannot be built from this repo — V8 was not the whole story

`implementation-plan.md:120` gives "a blank environment cannot be built from source" as the
reason to recover V8, which reads as though recovering it fixes the problem. **It does not.**
Measured 2026-10-08:

| | Count | Which |
|---|---|---|
| Tables these migrations create | **10** | `ref_account_status`, `ref_customer_role`, `ref_customer_status`, `ref_pump_type`, `ref_storage_type`, `ref_trip_status` (V3); `pump_flow_rate_config`, `pump_flow_rate_cache`, `trip_flow_rate_anomalies` (V7); `shop_location_config` (V8) |
| Tables production actually has | **23** | the 10 above plus the 13 below |
| Tables **no migration creates** | **13** | `rs_cust_dtls`, `rc_txn_header`, `rc_txn_details`, `rc_credit_req`, `rc_dishtv_dtls`, `rc_option_lookup_hdr`, `rc_option_lookup_dtl`, `rc_user`, `rc_usr_api_kys`, `rc_pn_tokens`, `wt_purchase_party`, `wt_purchase_details`, `wt_adt_lgs` |

Those 13 predate Flyway. The `<< Flyway Baseline >>` row at version 0 was taken against an
already-populated production database, so everything that existed at that moment is outside
version control by construction. V1 and V2 only `ALTER` those tables — V1's very first
statement is an `ALTER TABLE rs_cust_dtls`, guarded by an `INFORMATION_SCHEMA.COLUMNS` check
that returns 0 when the table is absent, so on a blank database V1 does not skip, it **fails**
with "table doesn't exist".

**Consequence:** building from blank needs a `V0`-style baseline migration carrying those 13
tables' DDL, reconstructed from the dump. That is real work, it is not V9–V12's business, and
nothing in the recharge module is blocked by it — so it is recorded here and left alone.

## How migrations are tested instead

`implementation-plan.md:69` states the S1 gate as "migrations apply to a **prod copy**", not
to a blank database, and that is both achievable and what `RechargeMigrationTest` does. It
loads `src/test/resources/db/legacy-baseline.sql` into a throwaway MySQL 8.0.43 container,
baselines Flyway at version 8 (where production sits), applies the new migrations on top, and
asserts the result with `INFORMATION_SCHEMA` queries in the style `design-ledger.md` §8.1 sets
out for V26.

The fixture is a hand-built, data-free stand-in rather than the production dump, for two
reasons. The dump is 9.2MB of real customer and transaction data and **this repository is
public**, so it must never be committed; and the gate needs structure — types, collations,
keys — not rows. Every value in the fixture was read from `INFORMATION_SCHEMA` on the
read-only restore, including the `utf8mb3` collations that V9 exists to convert and the
`mediumint` keys that `design-domain.md` §4.4 warns about.

**The test does not rehearse the V8 `flyway repair` above.** Baselining at version 8 means
Flyway never looks at the V8 file, so the checksum mismatch cannot arise in the container.
The repair remains a production step that nothing here exercises — run it once, as described,
before the first deployment that includes V8.

### Running the gate

The test is a normal `mvn test` and skips itself when Docker is absent, so CI without Docker
stays green — but a skipped gate proves nothing, so check that it actually ran.

Two settings are needed and are already in the repo: `api.version=1.44` in
`src/test/resources/testcontainers.properties` and as a Surefire system property in `pom.xml`
(Docker Engine 29 rejects the Testcontainers default of 1.32), and `testcontainers.version`
pinned above the Boot BOM in `pom.xml`.

Colima users additionally need this, because there is no `/var/run/docker.sock` to discover:

```sh
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export TESTCONTAINERS_DOCKER_CLIENT_STRATEGY=org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy
```

A stale `~/.testcontainers.properties` pinning `docker.client.strategy` to
`UnixSocketClientProviderStrategy` will defeat the first of those; the third overrides it.
Docker Desktop needs none of them.
