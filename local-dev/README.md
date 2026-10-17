# Running the counter yourself

**Yes, you can start the whole thing and click through it.** One command brings up
a throwaway database, the backend and the screens:

```sh
./local-dev/start.sh
```

Then open **http://localhost:4200** and sign in as `counterstaff` / `counter123`.
The counter is behind the **Recharge** tile; the close screen is behind **Day's
end & accounts**. Stop everything and throw the database away with:

```sh
./local-dev/stop.sh
```

It expects `shop-web` to sit next to this repo. If it lives somewhere else, run
`SHOP_WEB=/path/to/shop-web ./local-dev/start.sh`, or start it yourself with
`npx ng serve` and the script will skip that step.

Nothing it creates survives `stop.sh`. That is deliberate — see below.

---

## Running the owner's gate

This script is for a developer trying the counter. **The owner's gate is a different thing**
— real recharges, on a real day, against production, with the legacy app still running — and
it has its own procedure:

| | |
|---|---|
| [`OWNER-GATE.md`](OWNER-GATE.md) | the procedure: what to prepare, what to record, the drawer equation, and the four things that look like failures and are not |
| [`gate-preflight.sh`](gate-preflight.sh) | read-only checks that a database is ready. Safe against production. Run it before the day, not on it |

## Why there is a script at all, rather than just `mvn spring-boot:run`

⚠ **Do not start the application on the `local` profile to try things out.**

`application.properties` points `spring.datasource.url` at
`jdbc:mysql://localhost:3306/recharge` — **the developer restore** — with Flyway
enabled and **no `target`**. So a plain `mvn spring-boot:run` will:

- migrate the restore, to whatever the highest version in the repo happens to be
  that day;
- write counter sales into it; and
- **post vouchers to it.**

The third is the serious one. `next-session-s3.md` forbids it outright — *"the
ledger stays empty until the opening journal, and the absence of
`rc_setting['ledger.opening.date']` is how the system knows. Containers are where
vouchers get posted."* The restore is the project's only read-only record of what
the shop's data actually looks like, and a voucher in it cannot afterwards be told
apart from a real one.

So the `container` profile exists: a throwaway MySQL on **port 3307** —
deliberately not 3306, so a mistyped profile connects to nothing rather than to
the wrong database — with Flyway capped at 28.

**There is a worse version of this still waiting, and it is not fixed.** Nothing
caps Flyway in the application config; only the tests and the `container` profile
do. The day `V13` is written, a local run applies it, moves the `CRDT` rows out of
the item table, and the legacy writer starts failing. The `local` profile probably
should cap too, but changing it affects everyone who runs it, so it is raised
rather than done.

## Doing it by hand

The script is only these steps. Run them yourself if you want to vary something.

```sh
# 1. the database: the fixture the five container gates use, plus the five
#    pre-Flyway tables the application needs in order to start at all
docker run -d --name s3-counter-db \
  -e MYSQL_ROOT_PASSWORD=s3root -e MYSQL_DATABASE=recharge \
  -e MYSQL_USER=CISADM -e MYSQL_PASSWORD=s3pass \
  -p 3307:3306 \
  -v "$PWD/src/test/resources/db/legacy-baseline.sql:/docker-entrypoint-initdb.d/01-baseline.sql:ro" \
  -v "$PWD/local-dev/01-pre-flyway-remainder.sql:/docker-entrypoint-initdb.d/02-remainder.sql:ro" \
  mysql:8.0.43 --log-bin-trust-function-creators=1

# 2. the backend. Flyway runs V9-V12 and V20-V29 on first boot.
SPRING_PROFILES_ACTIVE=container \
DB_PASSWORD=s3pass \
JWT_SECRET=container-only-secret-not-for-any-real-deployment-0123456789 \
mvn spring-boot:run

# 3. the screens, from the shop-web repo
npx ng serve --port 4200
```

`--log-bin-trust-function-creators=1` is not tuning: MySQL 8 refuses
`CREATE TRIGGER` from a non-`SUPER` account while binary logging is on (ERROR
1419), so V23–V25 fail without it. The same flag is a **production prerequisite**
before V23 deploys — see `db/migration/README.md`.

The backend's context path is `/api`, so the counter API is at
`http://localhost:8080/api/recharge/...`. The `container` profile allows CORS from
`http://localhost:4200` and nowhere else.

### A staff user, because there is no sign-up for one

`/customer/register` only ever mints `ROLE_CUSTOMER`
(`CustomerRegistrationService.createCustomerUser`), which is deliberately locked
out of `/recharge/**`. So a counter user has to be inserted:

```sh
HASH=$(htpasswd -nbBC 10 x 'counter123' | cut -d: -f2)
docker exec s3-counter-db mysql -uCISADM -ps3pass recharge -e "
  INSERT INTO rc_user (USER_NAME, PASSWORD, ROLES, ACCOUNT_STATUS, CREATED_DATE)
  VALUES ('counterstaff', '$HASH', 'ROLE_USER', 'ACTIVE', NOW());"
```

`ROLE_USER` **is** the staff role — there is no `ROLE_STAFF` and one must not be
created (log §15.11). `ACCOUNT_STATUS` must be `ACTIVE` or `APPROVED`;
`AuthenticationController` refuses anything else.

## What to try, and what each thing is meant to show

| On the screen | What it demonstrates |
|---|---|
| **TV → Dish Tv → ₹240** | Our charge pre-fills at ₹10 and stays its own figure; the basket line reads ₹240 while the total reads ₹250 |
| **TV amount field** | A ±₹10 stepper and no preset chips — Dish TV's commonest amount is 16% of its volume against mobile's 78% (§14.10) |
| **Mobile → Jio / Airtel / Bsnl** | Per-operator preset chips, measured from real volume. **Vi deliberately has none** — see the open question in PR #5 |
| **Take payment, then "Yes — via A1 Topup"** | The two-stage posting: cash at settlement, income at completion |
| **Leave a sale on "did it go through?" and open Day's end** | ₹X held against N recharges, worded as money held and never as a till mismatch |
| **Worklist** | Normally empty, and reads as "nothing waiting" rather than an empty table |
| **History** | Genuinely blank until V15 runs at cutover, and says so rather than looking broken |

## Poking the API directly instead

```sh
TOKEN=$(curl -s -X POST http://localhost:8080/api/authenticate \
  -H 'Content-Type: application/json' \
  -d '{"username":"counterstaff","password":"counter123"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')

curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/recharge/config

RECHARGE=$(curl -s -X POST localhost:8080/api/recharge/transactions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"recharges":[{"serviceType":"TV","number":"02700000001",
                     "operatorCode":"DISHTV","amount":240.00}],
       "settlementLines":[{"method":"CASH","amount":250.00,"amountTendered":250.00}]}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["recharges"][0]["rechargeId"])')

curl -s -X POST "localhost:8080/api/recharge/recharges/$RECHARGE/complete" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'

curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/recharge/day-summary
```

That sale leaves exactly two vouchers, both sealed:

```sql
SELECT v.VOUCHER_ID, v.IDEMPOTENCY_KEY, l.ACCOUNT_CODE, l.DR_AMOUNT, l.CR_AMOUNT
  FROM acc_voucher v JOIN acc_voucher_line l ON l.VOUCHER_ID = v.VOUCHER_ID
 ORDER BY v.VOUCHER_ID, l.LINE_NO;
```

```
RECHARGE_SALE:rc_txn_header#1:SETTLED    CASH_RECHARGE       250.00        .
                                         RCHG_PENDING             .   250.00
RECHARGE_SALE:rc_recharge#1:COMPLETED    RCHG_PENDING        250.00        .
                                         FLOAT_A1TOPUP            .   231.36
                                         INC_RCHG_COMM            .     8.64
                                         INC_RCHG_SURCHARGE       .    10.00
```

## ⚠ What the till reconciles against — and two formulas that were wrong

```
the recharge till today  =  cashCollectedToday  ==  tenderMix[CASH]
```

Two **independent** readings of the same number: `cashCollectedToday` is the day's
net movement on `CASH_RECHARGE` read from the ledger, and `tenderMix[CASH]` is the
sum of today's cash tender rows. They must agree — a disagreement means a posting
is wrong rather than a count being off, and the close screen says so in those
words.

⚠ **`heldPending` is NOT a term in that equation.** It is the ALL-TIME,
ALL-TENDER open exposure, which is what log §7.2 built `RCHG_PENDING` to be. It
keeps money from recharges left unresolved on earlier days, and it includes UPI
and credit settlements that never reached the drawer. It *explains* why takings
sit below cash collected; it does not balance against the till.

**This section said something different twice, and both were wrong.** First
`drawer = totalTakings + heldPending`, which omits our own charge and is ₹10 short
on every DTH recharge. Then `drawer = totalCollected + heldPending`, which fixed
that and was still wrong for the two reasons above. Both balance only for
same-day, all-cash baskets — which was the shape of every test and every manual
check until one UPI sale and one day boundary were tried.

Found by running it, not by reading it, both times.
`RechargeHistoryServiceTest` now pins the right form as an equality and both wrong
ones as inequalities.
