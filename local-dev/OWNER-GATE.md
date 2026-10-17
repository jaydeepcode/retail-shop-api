# The owner's gate — running the counter alongside the legacy app

`implementation-plan.md:201` — the last thing S3 owes:

> **The real gate**: run real recharges alongside the legacy app and compare the day summary
> to the drawer. **Needs the owner, at the counter.**

Passing it is what opens S4. This file is the procedure; nothing in it needs a developer
present once the preparation below is done.

---

## What the gate actually proves

That **the cash the counter says it took is the cash in the drawer**, on a real day, with real
customers, with the legacy app still running as the book of record.

It is not a test of the screens — those were clicked through before PR #4 merged. It is the
one check no container can make: every test in this repo asserts the counter against its own
arithmetic, and this asserts it against physical money.

**The legacy app stays authoritative throughout.** `implementation-plan.md` §1.2: "the ledger
is not authoritative for recharge until the legacy writer is retired." Every sale is entered
in BOTH, the legacy app remains the record, and the new counter is the thing being judged. If
it disagrees, the legacy app wins and the gate has done its job.

---

## Before the day

Run these in order. Steps 1–3 need a developer; 4–5 are the owner's.

### 1. Take a dump you can go back to

```bash
mysqldump -h <host> -u CISADM -p recharge > recharge-pre-gate-$(date +%F).sql
```

P0.5. The migrations below are all "safe alongside the legacy app" (`implementation-plan.md`
§0.2), but a rollback point costs one command.

### 2. Repair V8 — once per database, and the app will not start without it

`V8__Shop_location_config.sql` was applied to production on 2025-12-07 and never committed;
the file in this repo is a reconstruction with a different checksum. Flyway runs `validate`
before `migrate`, so until this is repaired **the application does not start at all** and the
new migrations never get a chance to apply.

```bash
mvn flyway:repair      # see src/main/resources/db/migration/README.md
```

### 3. Apply the counter's migrations

Start the backend once against the target database. Flyway applies **V9–V12 and V20–V29**,
capped at 29 — `spring.flyway.target=29` in `application.properties`, inherited by both the
`local` and `prod` profiles.

> ⚠ **V9 takes an exclusive lock on four live tables** while InnoDB rebuilds them
> (`ALGORITHM=COPY`, ~13 MB, measured at 0.34 s on 2026-10-08). The legacy writer is *blocked*,
> not slowed, for that moment. **Run it in a quiet minute**, not mid-afternoon.

> ⚠ **The cap is what keeps V13–V19 out.** Those are cutover work: V13 moves the `CRDT` rows
> out of the item table and V14 adds `CHECK (amt_returned >= 0)`, which breaks the legacy
> app's next UPI write. Raising the cap is a deliberate act, never a side effect of adding a
> migration file.

### 4. Pre-flight

```bash
DB_HOST=<host> DB_PORT=3306 DB_USER=CISADM DB_PASS=<pass> \
  ./local-dev/gate-preflight.sh
```

Read-only, safe against production. It must print **Ready for the gate**. Every failure it
reports names what to do. Two of its checks exist because the failure already happened once:
a sellable operator with no commission rate on its default route sells and then *cannot be
completed*, stranding the customer's cash in `RCHG_PENDING` (that was BIGTV, log §8.4).

### 5. Count the drawer before the first sale

Write the opening float down **on paper**. Everything below is a comparison against it, and a
remembered number is not a number.

---

## On the day

1. **Every recharge goes into both apps.** The legacy app first — it is still the record.
2. **Keep the till discipline normal.** Do not sort cash differently for the gate; the point
   is to measure the ordinary day.
3. **Write down ticket takings separately.** See the warning below — this is the one number
   the system cannot give you and the comparison needs.
4. **Do not resolve anything you would not normally resolve.** A recharge left awaiting at
   close is a realistic input, not a spoiled result.

---

## At close — the comparison

Open **Day's end & accounts**, or `GET /api/recharge/day-summary`.

### The drawer equation

```
  counted POOL-1 cash
−  opening float            (step 5)
−  ticket cash taken today  (on paper — see below)
=  cashCollectedToday       (the day summary)
```

### The internal check, which should hold on its own

```
  cashCollectedToday = tenderMix[CASH] − cashPaidOutToday
```

Two independent readings: the first from the ledger's `CASH_RECHARGE` movement, the second
from the tender lines, less anything handed back as a refund or a retry residual. **If those
two disagree, a posting is wrong** — that is a defect to report, not an arithmetic slip.

---

## What is expected, and must not be read as a failure

| What you will see | Why it is correct |
|---|---|
| **The drawer exceeds `cashCollectedToday` by the day's ticket takings** | Recharge and Ticket share one physical drawer (POOL-1, log §3) and ticket booking is **S4 — not built**. Nothing records it yet. This gap is structural and known; it closes when S4 lands. |
| **Cash collected exceeds takings** | Takings count `COMPLETED` recharges only. Cash is collected at settlement. Any recharge still awaiting or failed is money held, not money earned. §7.1(c) is explicit the warning should read "₹X held against N unresolved recharges", **never** "till mismatch". |
| **`heldPending` does not close that gap exactly** | It is the **all-time, all-tender** open exposure — it carries money from previous days and includes UPI and credit, which never entered the drawer. It explains the gap; it is not a term in the equation. Two earlier versions of this project stated an identity using it and both were wrong (§0.6). |
| **UPI sales are nowhere in the drawer** | They never touched it. They are in `tenderMix[UPI]` and in `UPI_CLEARING`. |
| **A credit sale collects nothing today** | It is a receivable. It appears on the credit book, not in the till. |
| **BIGTV commission posts as zero** | A provisional rate (log §15.7), absent from the portal list. It does not move the drawer, so it cannot fail the gate. |
| **Vi offers no amount presets** | Known and open (log §14.10). Staff type the amount. One `INSERT` fixes it when the owner decides the amounts. |

---

## A worked example

Driven through a running counter on 2026-10-17, so the figures below are read back from a
real day's shape rather than constructed: five cash sales, one UPI, one on credit, one left
awaiting at close, and one failure refunded in cash.

| Sale | Tender | Outcome |
|---|---|---|
| Jio 299 | cash 299 | completed |
| BSNL 147 | cash 147 | completed |
| Dish TV 240 + 10 surcharge | cash 250 | completed |
| Airtel 199 | cash 199 | **left awaiting at close** |
| Jio 349 | UPI 349 | completed |
| Jio 49 | credit 49 | completed |
| BSNL 100 | cash 100 | **failed, refunded in cash** |

The day summary:

```
takings        1084.00  + surcharge 10.00  = collected 1094.00
tenderMix      CASH 995.00   UPI 349.00   CREDIT 49.00
cashPaidOut     100.00
cashCollected   895.00
heldPending     199.00    awaiting 1    unresolved failures 0
```

**The drawer.** Cash in: 299 + 147 + 250 + 199 + 100 = 995. Cash out: the 100 refund. So the
till should hold the opening float **plus 895**, and `cashCollectedToday` says 895. The
ledger agrees independently — `CASH_RECHARGE` is 995 debit against 100 credit.

**Why takings (1084) and cash collected (895) differ, in both directions at once:**

- the UPI 349 and the credit 49 are in takings but never entered the drawer;
- the awaiting Airtel 199 is cash in the drawer but not in takings, because takings count
  `COMPLETED` only — and `RCHG_PENDING` carries exactly that 199, which is what
  `heldPending` reports;
- the refunded 100 is in neither: collected and handed back the same day.

None of that is a discrepancy. It is three different true statements about one day, and the
only one the drawer can check is the first.

---

## Afterwards — the checks the drawer cannot make

The gate is about cash. Two things it does not test, worth doing in the same week:

- **The provisional commission rates.** Three direct rates are still from memory and BIGTV is
  a provisional zero. Sell one recharge on each, then read the portal debit: the float leg
  should equal `A − round(A × (1 − r), 2)`. Self-correcting, and the only way to confirm them.
- **The float.** `FLOAT_A1TOPUP`, `FLOAT_ARTL`, `FLOAT_RJIO`, `FLOAT_VI` should have moved by
  what the operator portals say was consumed.

---

## Stop conditions

End the gate and report, rather than working around it, if:

- the two independent cash readings disagree (a posting is wrong);
- a recharge settles and then cannot be completed (cash stranded in `RCHG_PENDING`);
- the legacy app starts refusing writes (a cutover migration has been applied — the
  pre-flight exists to prevent exactly this, so say so loudly);
- the day summary and the legacy app's own recharge total disagree by more than the
  unresolved items explain.

A failed gate is a successful gate. It is the last chance to find this before cutover, when
the legacy app is switched off and there is nothing to compare against.
