#!/usr/bin/env bash
#
# Owner's gate pre-flight — checks a database is ready for the parallel run.
#
#   ./local-dev/gate-preflight.sh                      # the local-dev container
#   DB_HOST=... DB_PORT=3306 DB_NAME=recharge \
#   DB_USER=CISADM DB_PASS=... ./local-dev/gate-preflight.sh
#
# READ-ONLY. It asserts preconditions and changes nothing, so it is safe to run
# against production — which is the database the gate actually runs on
# (implementation-plan.md §0.2: "the new counter can be built, deployed and used
# while the old one still runs").
#
# Exit 0 = ready. Exit 1 = at least one check failed; each prints what to do.
set -uo pipefail

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3307}"
DB_NAME="${DB_NAME:-recharge}"
DB_USER="${DB_USER:-CISADM}"
DB_PASS="${DB_PASS:-s3pass}"

# The head this build expects. Keep in step with spring.flyway.target.
EXPECTED_HEAD=29

failures=0
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; failures=$((failures + 1)); }
note() { printf '    %s\n' "$1"; }
head2() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ⚠ -N prints a SQL NULL as the four characters "NULL", not as an empty string,
# so an empty GROUP_CONCAT comes back truthy and an "is anything wrong?" check
# reads as "yes, something called NULL". Normalised here once rather than at
# every call site — this script reported a phantom failure before it did.
q() {
  local out
  out=$(mysql --protocol=TCP -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASS" \
              -D "$DB_NAME" -N -B -e "$1" 2>/dev/null)
  [ "$out" = "NULL" ] && out=""
  printf '%s' "$out"
}

printf '\033[1mOwner'"'"'s gate pre-flight — %s:%s/%s\033[0m\n' "$DB_HOST" "$DB_PORT" "$DB_NAME"

if ! q "SELECT 1" >/dev/null; then
  printf '\n\033[31mCannot connect.\033[0m Check DB_HOST/DB_PORT/DB_USER/DB_PASS.\n'
  exit 1
fi

# ---------------------------------------------------------------- schema
head2 "1. Schema"

applied_head=$(q "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0)
                    FROM flyway_schema_history WHERE success = 1")
if [ "${applied_head:-0}" = "$EXPECTED_HEAD" ]; then
  pass "Flyway head is V$applied_head"
else
  fail "Flyway head is V${applied_head:-none}, expected V$EXPECTED_HEAD"
  note "Start the backend once with the capped profile to apply the rest."
fi

# The one that must NOT have happened. V13 moves the CRDT rows out of the item
# table and V14 adds CHECK (amt_returned >= 0), which breaks the legacy app's
# next UPI write — and the legacy app is still the book of record during the gate.
cutover=$(q "SELECT COUNT(*) FROM flyway_schema_history
              WHERE success = 1 AND CAST(version AS UNSIGNED) BETWEEN 13 AND 19")
if [ "${cutover:-0}" = "0" ]; then
  pass "no cutover migration (V13-V19) has been applied"
else
  fail "$cutover cutover migration(s) applied — the legacy writer may already be broken"
  note "STOP. This database has begun cutover; the gate cannot run alongside the legacy app."
fi

# V8's checksum mismatch stops Flyway before migrate, so the app will not start
# at all until repair has been run once on this database.
# The original V8 was applied to production on 2025-12-07 and never committed;
# the file here is a reconstruction with a different checksum. Flyway runs
# validate before migrate, so until repair is run once the application will not
# start AT ALL against a database carrying the original row.
v8=$(q "SELECT checksum FROM flyway_schema_history WHERE version = '8' AND success = 1")
if [ "${v8:-}" = "1287378492" ]; then
  fail "V8 still carries the original checksum — Flyway validate will refuse to start"
  note "Run once on THIS database:  mvn flyway:repair   (db/migration/README.md)"
elif [ -n "${v8:-}" ]; then
  pass "V8 has been repaired on this database (checksum $v8)"
else
  # A baseline row carries no checksum, which is the container's shape and is
  # also what a freshly baselined database looks like. Nothing to repair.
  pass "V8 carries no checksum to mismatch (baseline row, or already repaired)"
fi

# ---------------------------------------------------------------- ledger
head2 "2. Ledger"

opened=$(q "SELECT COUNT(*) FROM rc_setting WHERE SETTING_KEY = 'ledger.opening.date'")
if [ "${opened:-0}" = "0" ]; then
  pass "the ledger has not been opened — opening balances are S6 (V30/V31)"
else
  note "ledger.opening.date is set; the gate compares the DAY'S MOVEMENT either way"
fi

# The gate compares one day's movement, so a non-empty ledger is fine — but the
# owner should know whether today's figures start from zero.
vouchers=$(q "SELECT COUNT(*) FROM acc_voucher")
pass "acc_voucher holds ${vouchers:-0} voucher(s) before the gate"

pool1=$(q "SELECT GROUP_CONCAT(ACCOUNT_CODE ORDER BY ACCOUNT_CODE)
             FROM acc_account WHERE CASH_POOL = 'POOL-1'")
if [ "${pool1:-}" = "CASH_RECHARGE,CASH_TICKET" ]; then
  pass "POOL-1 is $pool1"
  note "⚠ the physical drawer also holds TICKET cash, and tickets are S4 — unrecorded."
  note "  The count will EXCEED CASH_RECHARGE by the day's ticket takings. Record them."
else
  fail "POOL-1 is '${pool1:-empty}', expected CASH_RECHARGE,CASH_TICKET"
fi

# ---------------------------------------------------------------- counter
head2 "3. Counter configuration"

staff=$(q "SELECT COUNT(*) FROM rc_user WHERE ACCOUNT_STATUS = 'ACTIVE'")
if [ "${staff:-0}" -gt 0 ]; then
  pass "$staff active staff user(s) can sign in"
else
  fail "no active staff user — nobody can sign in to the counter"
fi

sellable=$(q "SELECT COUNT(*) FROM rc_operator WHERE IS_ACTIVE = 1 AND RETIRED_ON IS NULL")
pass "$sellable operators are sellable"

# Every sellable operator needs a rate on its default route, or the recharge
# settles and then cannot be completed — log §8.4's BIGTV defect, which was
# found this way.
norate=$(q "SELECT GROUP_CONCAT(o.OPERATOR_CODE ORDER BY o.OPERATOR_CODE)
              FROM rc_operator o
             WHERE o.IS_ACTIVE = 1 AND o.RETIRED_ON IS NULL
               AND NOT EXISTS (SELECT 1 FROM acc_commission_rate r
                                WHERE r.OPERATOR_CODE = o.OPERATOR_CODE
                                  AND r.ROUTE_CODE = o.DEFAULT_ROUTE_CODE
                                  AND r.EFFECTIVE_FROM <= CURDATE()
                                  AND (r.EFFECTIVE_TO IS NULL OR r.EFFECTIVE_TO >= CURDATE()))")
if [ -z "${norate:-}" ]; then
  pass "every sellable operator has a rate on its default route"
else
  fail "no commission rate on the default route for: $norate"
  note "These sell but cannot be COMPLETED — the cash strands in RCHG_PENDING."
fi

provisional=$(q "SELECT COUNT(*) FROM acc_commission_rate
                  WHERE NOTE LIKE '%rovisional%'
                    AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO >= CURDATE())")
if [ "${provisional:-0}" -gt 0 ]; then
  note "⚠ $provisional commission rate(s) are still PROVISIONAL (from memory, and"
  note "  BIGTV at zero). They do not move the drawer, so they cannot fail the gate —"
  note "  but check each against the portal debit afterwards. Sell one of each."
fi

nopresets=$(q "SELECT GROUP_CONCAT(o.OPERATOR_CODE ORDER BY o.OPERATOR_CODE)
                 FROM rc_operator o
                WHERE o.IS_ACTIVE = 1 AND o.RETIRED_ON IS NULL
                  AND o.RECHARGE_KIND = 'MOBILE'
                  AND NOT EXISTS (SELECT 1 FROM rc_operator_preset p
                                   WHERE p.OPERATOR_CODE = o.OPERATOR_CODE)")
if [ -n "${nopresets:-}" ]; then
  note "⚠ no amount presets for: $nopresets — staff type the amount by hand."
  note "  Known and open (log §14.10): one INSERT into rc_operator_preset fixes it."
fi

# ---------------------------------------------------------------- open items
head2 "4. Open items carried in"

awaiting=$(q "SELECT COUNT(*) FROM rc_recharge WHERE STATUS_CODE = 'AWAITING_RECHARGE'")
failed=$(q "SELECT COUNT(*) FROM rc_recharge WHERE STATUS_CODE = 'FAILED'")
held=$(q "SELECT COALESCE(SUM(l.CR_AMOUNT) - SUM(l.DR_AMOUNT), 0)
            FROM acc_voucher_line l JOIN acc_voucher v ON v.VOUCHER_ID = l.VOUCHER_ID
           WHERE v.SEALED_AT IS NOT NULL AND l.ACCOUNT_CODE = 'RCHG_PENDING'")
pass "carried in: ${awaiting:-0} awaiting, ${failed:-0} unresolved failures, ${held:-0} held"
note "Money held against unresolved recharges is why cash can exceed takings."
note "It is correct, not a mismatch (log §7.1(c))."

# ---------------------------------------------------------------- verdict
head2 "Verdict"
if [ "$failures" -eq 0 ]; then
  printf '  \033[32mReady for the gate.\033[0m Record the opening drawer count before the first sale.\n\n'
  exit 0
fi
printf '  \033[31m%d check(s) failed.\033[0m Fix them before the gate day.\n\n' "$failures"
exit 1
