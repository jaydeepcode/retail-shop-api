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

## The ledger chain is V21-V27 here, not the V20-V26 the design names

`design-ledger.md` §8.1 numbers the ledger migrations V20-V26 and its opening-balance
work V27-V28. **In this repository they are V21-V27, and the opening balances are now
V29-V30**, because V20 is taken by a recharge-catalogue change the ledger depends on and
V28 is taken by the counter's own prerequisites. The opening balances have therefore
shifted twice; they are S6 go-live work and apply after everything here regardless, so
each shift costs nothing but a renumber.

`V20__Operator_catalog_vi_merge.sql` merges the `IDEA` and `VDFN` operators into a single
`VI`, per `recharge-technical-decisions.md` §2.4:84-105 — *"four accounts, mapped by route
not operator"*, with *"Vi is one wallet behind two operator codes"*. It is not ledger work,
and `design-ledger.md:238` warns against a ledger migration reaching into `rc_operator`.
It is numbered here anyway because it cannot go anywhere else:

- `out-of-order=false`, so a migration only applies if its version is above everything applied.
- **V13-V19 are reserved** cutover work that breaks the live legacy writer, so numbering it
  there would make it un-runnable without also running the cutover set.
- V21's chart of accounts foreign-keys the operator code `VI`, so it must land *before* it.

**Why the merge is needed at all.** The shop holds four float wallets, not one per operator
(§14.9 measures them: A1Topup ₹87,018, Jio ₹66,568, Airtel ₹66,239, Vi ₹39,606). The float
account is resolved at runtime from operator and route together, with no mapping table:

```
route = 'A1TOPUP'  ->  FLOAT_A1TOPUP
route = 'DIRECT'   ->  CONCAT('FLOAT_', OPERATOR_CODE)
```

That derivation is total **only** once `IDEA` and `VDFN` are one code. Two operator codes
behind one wallet is the single case it cannot express. Confirmed with the owner 2026-10-08.

`IDEA` and `VDFN` are retired (`IS_ACTIVE = 0`), never deleted, because older `rc_recharge`
rows will foreign-key them. A retired operator still satisfies `fk_rech_operator` —
`IS_ACTIVE` governs the picker, not referential integrity.

**V15's backfill splits by date**, decided 2026-10-09 (`recharge-technical-decisions.md`
§15.8, `design-domain.md` §3.2):

| Rows | Operator | Route |
|---|---|---|
| `SRVTEI` / `SRVTEV` in the current FY | `VI` | `A1TOPUP` |
| `IDEA` / `VDFN` in the current FY | `VI` | `DIRECT` |
| Everything older | left exactly as typed | unchanged |

The front end only shows current-FY data, so an inconsistency behind that boundary is
invisible and rewriting seven years of history to tidy it buys nothing observable.

## V28 carries four things three documents decided and no migration built

`V28__Recharge_counter_prerequisites.sql` is S3's own migration. Nothing in it is a new
design decision; each part is a decision that was recorded and then never reached a file.
Found by building the counter against the schema rather than against the documents.

| | What | Decided in | Why it was missing |
|---|---|---|---|
| 1 | `rc_recharge.SURCHARGE` | `design-api.md` §3.3, log §10.2, §15.11 | `design-domain.md` §4.4's DDL never gained the column, so V12 faithfully created the table without it. V21 had already seeded `INC_RCHG_SURCHARGE`, so **the income account existed with nothing to post to it.** |
| 2 | `surcharge.default.tv` / `.mobile` in `rc_setting` | log §15.11 | §3.3 defaults an absent surcharge to "the configured amount for the service type"; there was no configured amount anywhere. |
| 3 | `rc_operator_preset` replaced with the measured sets | log §14.10 | V10's own header calls its amounts "the design's proposal ... Treat the values as provisional, not as a decision". §14.10 is the confirmation and it contradicts the proposal structurally, not numerically. |
| 4 | `rc_credit_req`: three columns, and `CRED_DTTM_UNIQUE` dropped | `design-domain.md` §4.5, §6.4 | Assigned to **V17**, which sits behind V13/V14 in a chain `out-of-order=false` will not let S3 skip. |

**Item 4 is the one that blocks rather than annoys.** `CRED_DTTM_UNIQUE` is UNIQUE on
`CRED_DTTM` alone, so it forbids two *different* customers being given credit in the same
second — "not a business rule; it is an accident" (§6.4). It holds today only because one
operator has been typing at human speed for seven years (4,301 of 4,301 distinct). The
counter writes a credit row inside the settle transaction, so the first time two staff
settle concurrently it throws from inside a transaction carrying a sale.
`WaterTransactionService.populateCreditDebitRecord` shares the bug today.

**Item 3 has one figure worth a second look, flagged rather than decided.** §14.10 measured
the pre-merge `VI` code at 23 recharges — "too thin to preset" — and measured `IDEA`
separately at 199/299/26/179. V20 then merged `IDEA` and `VDFN` into `VI`, so `VI` now
inherits that volume while the presets measured for it belong to a retired code.
Implemented literally, which leaves the shop's third-largest float with no amount chips. If
the owner wants `VI` to inherit `IDEA`'s set it is one `INSERT` into a table built to be
hand-edited — no release, no code change.

### What V17 keeps when cutover writes it

V28 deliberately left two of §4.5's items behind, and V17 must guard its remaining `ALTER`s
the way V28 does or it will fail on a database that already has V28.

- **The two placeholder `rs_cust_dtls` rows (`CUST_ID` 191 and 311) and the FK
  `CUST_ID -> rs_cust_dtls` they enable.** Inserting rows into `rs_cust_dtls` is writing to
  the book of record, and S6 is the only slice allowed to. Without them the FK fails errno
  1452. S3 does not need it: the counter resolves a customer through
  `CustomerDirectoryService` before writing a credit row, so the guarantee it relies on is a
  service check on the way in.
- **FK `req_type -> ref_credit_req_type`.** V9 seeded the reference table, but the
  constraint needs every one of the 4,301 existing `req_type` values to already be a seeded
  code, and that has only been verified against the brief, not the live column. An
  unverified constraint on the book of record is exactly the shape of failure that stops a
  migration halfway.

## V29 makes one-retry-per-failure a database guarantee

`design-api.md` §7's threat table, row 3, specifies **a unique key on
`FAILED_RECHARGE_ID`** and §7.2 then leans on it — #12 needs no `Idempotency-Key` header
*because* "it is guarded by the unique retry key". V12 created the column with a plain
`KEY idx_rech_retry`, so neither guarantee existed. V29 adds `uq_rech_retry` and drops the
redundant plain index, in that order: `fk_rech_retry` needs an index on the column at all
times, so dropping first would fail errno 150.

This is the **eighth** prerequisite a document decided and no migration built, found the
same way as `implementation-plan.md` §0.6's seven — by building against the schema rather
than against the documents.

**It pushes the opening balances once more, to V30/V31.** They have now moved from V27/V28
to V28/V29 to V29/V30 to V30/V31. That is still free for the reason stated above: they are
S6 go-live work and apply after everything here regardless.

### ⚠ What V13 must now do, and could not be written here

`RcTxnPaymentRepository.tenderMixOn` — the day summary's tender mix — is dated by
`rc_txn_payment.CRE_DTTM` rather than by the header's `TXN_DTTM`. It had to change: a retry
at a higher amount appends a tender line to the **original** payment (log §7.4), so a
top-up collected today can hang off a header dated yesterday, and header-dating would add
it to yesterday's tender mix while its cash posting landed today — breaking the
independent-sources cross-check §7.1(c) builds the day summary around, on both days.

`design-domain.md` §6's **V13 spec inserts historical `CRDT`, `UPI` and `CASH` lines into
`rc_txn_payment` without naming `CRE_DTTM`**, which would take V12's
`DEFAULT CURRENT_TIMESTAMP` — the migration's own run date. Harmless under the old query;
under this one it would file all 2,850 migrated baskets into the tender mix of cutover day.

**V13 must set `CRE_DTTM = h.TXN_DTTM` on every row it inserts.** It is reserved cutover
work, so it cannot be written or corrected from here — only recorded, the same way V8's
outstanding `repair` is.

## ⚠ One deployment step the ledger needs, and it is not in a migration

V23 and V24 create triggers, and MySQL 8.0.43 ships with `log_bin = ON` and
`log_bin_trust_function_creators = OFF`. In that state `CREATE TRIGGER` by an account without
`SUPER` fails outright:

```
ERROR 1419 (HY000): You do not have the SUPER privilege and binary logging is enabled
(you *might* want to use the less safe log_bin_trust_function_creators variable)
```

Measured on a clean `mysql:8.0.43` against an account holding `GRANT ALL PRIVILEGES ON
recharge.*` — so `TRIGGER` is granted, `SUPER` is not:

| `log_bin` | `log_bin_trust_function_creators` | `SUPER` | `CREATE TRIGGER` |
|---|---|---|---|
| ON | OFF | no | **ERROR 1419** |
| ON | ON | no | succeeds |
| ON | OFF | yes | succeeds |

**`CREATE PROCEDURE` is not affected** — also measured. That matters because V20 and V27 both use
`DELIMITER` to create a throwaway assertion procedure, and those are free. Only the two
trigger-creating migrations need anything.

Preferred remedy, least privilege. **Use `SET PERSIST`, not `SET GLOBAL`** — it sets the running
value *and* persists it, so there is no `my.cnf` edit to remember and nothing to lose on restart.
Run once per database, as root or a DBA account:

```sh
mysql -u root -p -e "SET PERSIST log_bin_trust_function_creators = 1;"
```

Verified on 8.4.11: it writes the value to `mysqld-auto.cnf` in the data directory
(`/opt/homebrew/var/mysql/` on the Homebrew install) and survives a restart. Confirm it with an
ordinary account, which can read the variable without any privilege:

```sh
mysql -h127.0.0.1 -uCISADM -pcisadm -N -e "SELECT @@log_bin_trust_function_creators;"
```

The alternative is `SUPER` on the migrating account, which is not a sensible grant for an
application account just to run two migrations.

**⚠ The variable is deprecated in the 8.x line.** It works on both 8.0.43 and 8.4.11 — both tested
— but expect a deprecation warning, and it may be removed in a future major. The forward-looking
replacement is granting `SET_USER_ID` to the migrating account instead of `SUPER`.

MySQL calls the variable "less safe" because on **shared hosting** a low-privilege user could
define a routine that runs with the definer's rights. One shop, one application, one owner who is
also the DBA — the risk here is negligible.

**The failure mode is a migration that stops halfway:** V21 and V22's tables land, V23 fails, and
the application will not start. It is recoverable — set the variable and re-migrate, since V23 is
re-runnable — but it should not be discovered on a go-live morning.

`LedgerMigrationTest` passes `--log-bin-trust-function-creators=1` to its container rather than
granting its user `SUPER`, so the gate exercises the least-privilege path production should use.

### ⚠ The developer restore is on 8.4.11; the gate pins 8.0.43

Measured 2026-10-09: the Homebrew MySQL serving the developer restore is **8.4.11**, while
`LedgerMigrationTest` and `RechargeMigrationTest` both pin `mysql:8.0.43` because that is the
version this file records for production. So the restore has been running a version neither the
gate nor production exercises.

For this slice that gap is closed by hand — V20–V27 were applied to a throwaway **8.4.11** as well
as 8.0.43, reaching v27 with 5 triggers, 32 accounts and 12 rate rows on both, and
`--log-bin-trust-function-creators=1` is accepted on both.

**Worth confirming whether production is still on 8.0.43.** If it has also been upgraded, the pin
in both migration tests should move, because a gate pinned to a version nothing runs is testing the
wrong thing. The pin is deliberate — see `RechargeMigrationTest`'s comment — so it should be
changed knowingly rather than floated to `8`.

### The accounts, named rather than left as placeholders

| | Account |
|---|---|
| Developer restore | **`'CISADM'@'localhost'`** — `application.properties:9` defaults `${DB_USERNAME:CISADM}`, confirmed by `CURRENT_USER()` |
| Production | **`${DB_USERNAME}`** — no default; supplied by the environment and deliberately not in this repository |

`@'localhost'` and `@'%'` are **different accounts** in MySQL, so a statement aimed at
`'CISADM'@'%'` would have no effect on the application at all. This file has made the
placeholder mistake before — see the `<host>` warning in the V8 repair section.

### ⚠ Per-table grants were tried instead of two of the triggers, and reverted

Recorded because the reasoning is not obvious and someone will suggest it again.

Append-only on `acc_voucher_line` and `acc_float_movement` does not strictly need a trigger. A
per-table grant that simply never grants `UPDATE` gives the same guarantee with no procedural code
in the database — verified: `UPDATE` refused with ERROR 1142 while `INSERT`, `SELECT` and `DELETE`
still work and the header's seal `UPDATE` is unaffected. It was adopted on 2026-10-09 to keep
storage rules out of the database, and reverted the same day once the real account was inspected:

- **`CISADM` holds `GRANT ALL PRIVILEGES ON `recharge`.*`, a database-level grant.** MySQL cannot
  narrow that per table — `REVOKE UPDATE ON recharge.acc_voucher_line` fails with **ERROR 1147**,
  "there is no such grant defined". So the grant route means `REVOKE ALL` and then re-granting per
  table: **36 base tables today**, plus the ledger's 13 and a view, plus everything V28–V31 adds.
- **It would have to be redone every time a migration adds a table**, from a root connection,
  because the app account has no `GRANT OPTION` and cannot grant to itself. A forgotten grant is a
  **runtime** failure at the counter, not a migration failure.
- **And it saves nothing.** The cross-row triggers need
  `log_bin_trust_function_creators = 1` regardless, and once that is set these two are free.

**What did not revert is the part that mattered.** The policy/storage split stands: the FRD §8.3
reconciliation delete lock and commission-rate immutability are **accounting policy** and live in
`LedgerCorrectionRules`, not in the database, where they return a sentence instead of
`SIGNAL SQLSTATE '45000'`. Only the mechanism for the storage half went back to triggers.

### What is enforced where

| Rule | Where | Why |
|---|---|---|
| a voucher's legs sum to zero at seal | `trg_acc_voucher_bu` | cross-row; nothing else can express it |
| a sealed voucher is never altered or un-sealed | `trg_acc_voucher_bu` | same trigger |
| no leg may be added to a sealed voucher | `trg_acc_voucher_line_bi` | without it the seal check is bypassable |
| `acc_voucher_line` is append-only | `trg_acc_voucher_line_bu` | storage property |
| the `OPENING` voucher is never deletable | `trg_acc_voucher_bd` | nothing to re-post it from |
| `acc_float_movement` is append-only | `trg_acc_float_movement_bu` | storage property |
| a reconciled account needs a journal (FRD §8.3) | `LedgerCorrectionRules` | **policy** — deserves a sentence |
| a rate is closed and superseded, never edited | `LedgerCorrectionRules` | **policy** — and §4.1 says the posted amount was always the real guarantee |

The service validates the balance itself as well, and that duplication is deliberate: the database
copy exists for writers that are not the application, which in this schema is a documented
operating mode rather than a hypothesis (requirement 10 has config "edited in tables with no
screen, i.e. by hand in SQL").

**⚠ The two service rules have no caller yet.** S2 ships no delete, void or rate-admin path, so
until S5 wires them nothing stops hand-SQL deleting a reconciled voucher's lines or editing a rate
row. They are written and tested so the rule demonstrably exists in the backend — removing a
trigger and writing nothing would be deleting the rule, not moving it.

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

### Three gates, each capped at its own slice

There is one gate per slice, and each caps Flyway at the chain that slice ships. That is what
lets all three stay correct at once:

| Gate | Flyway target | Asserts |
|---|---|---|
| `RechargeMigrationTest` | **12** | S1's reference tables, the operator catalogue, and V10's *provisional* presets |
| `LedgerMigrationTest` | **27** | S2's chart of accounts, the voucher tables, and all five triggers both existing and firing |
| `RechargeCounterMigrationTest` | **28** | S3's surcharge column, the measured presets, and `rc_credit_req` |

`RechargeMigrationTest` still asserting 45 presets while `RechargeCounterMigrationTest`
asserts 12 is not a contradiction: at V12 there genuinely are 45, and V28 is what replaces
them. Bolting V28's assertions onto an existing gate would have forced one of the earlier
ones to stop describing its own slice.

**V13-V19 are excluded by all three**, and raising a cap does not relax that — V28 is above
the reserved block, not inside it.

Note that two of the new gate's assertions are **writes, not `INFORMATION_SCHEMA` reads**: a
negative surcharge must be refused, and two customers must be able to take credit in the same
second. A dropped index and an index that never existed look identical to a structural check,
and the failure being guarded against is a write failing.

### Running the gate

The test is a normal `mvn test` and skips itself when Docker is absent, so CI without Docker
stays green — but a skipped gate proves nothing, so check that it actually ran. With all
three gates plus the two ledger service tests, a genuine local run is **138 tests with 0
skipped**; the same command with Docker unreachable is 138 with 52 skipped and still exits 0.

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
