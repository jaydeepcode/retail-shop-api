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

### One-off action still outstanding on production

The reconstructed bytes do not hash to `1287378492`, so Flyway's startup validation fails
with a checksum mismatch on version 8 until the stored checksum is rewritten once:

```
mvn flyway:repair \
  -Dflyway.url="jdbc:mysql://<host>:3306/recharge" \
  -Dflyway.user="<user>" \
  -Dflyway.password="<password>"
```

`repair` rewrites the checksum of the existing version-8 history row to match the file. It
does **not** re-run the migration, and it does not touch the schema or any data. Run it once,
before the first deployment that includes this file.

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
to a blank database, and that is both achievable and what `ProdCopyMigrationIT` does: it
loads `rechargeData.sql` — the production dump, which *is* the baseline — into a throwaway
MySQL container, applies the new migrations on top, and asserts the resulting schema with
`INFORMATION_SCHEMA` queries in the style `design-ledger.md` §8.1 sets out for V26.

Because the dump carries production's own `flyway_schema_history`, that container reproduces
the V8 checksum mismatch described above, and the test runs `repair` before `migrate` exactly
as production will have to. That makes the test the rehearsal for the production step, rather
than a path that quietly avoids it.
