# Running this application safely

There was no safe way to start this application before S3, and that is why this
directory exists.

## ⚠ Do not start it on the `local` profile to try things out

`application.properties` sets `spring.datasource.url` to
`jdbc:mysql://localhost:3306/recharge` — **the developer restore** — with Flyway
enabled and **no `target`**. So `mvn spring-boot:run` against it will:

- migrate the restore, and migrate it to whatever the highest version in the repo
  happens to be on the day you run it;
- write counter sales into it; and
- **post vouchers to it**, which `next-session-s3.md` forbids outright: *"the
  ledger stays empty until the opening journal, and the absence of
  `rc_setting['ledger.opening.date']` is how the system knows. Containers are
  where vouchers get posted."*

The third one is the serious one. The restore is the project's only read-only
record of what the shop's data actually looks like, and a voucher in it cannot be
distinguished later from a real one.

**There is a worse version of this waiting.** Nothing caps Flyway in the
application config — only the tests do. The day `V13` is written, a local run
applies it, moves the `CRDT` rows out of the item table, and the legacy writer
starts failing. The `container` profile below caps at 28; the `local` profile does
not, and probably should.

## The `container` profile

A throwaway MySQL on **port 3307** — deliberately not 3306, so a mistyped profile
connects to nothing rather than to the wrong database.

```sh
# 1. the database: the same fixture the five container gates use, plus the five
#    pre-Flyway tables the application needs in order to start at all
docker run -d --name s3-counter-db \
  -e MYSQL_ROOT_PASSWORD=s3root -e MYSQL_DATABASE=recharge \
  -e MYSQL_USER=CISADM -e MYSQL_PASSWORD=s3pass \
  -p 3307:3306 \
  -v "$PWD/src/test/resources/db/legacy-baseline.sql:/docker-entrypoint-initdb.d/01-baseline.sql:ro" \
  -v "$PWD/local-dev/01-pre-flyway-remainder.sql:/docker-entrypoint-initdb.d/02-remainder.sql:ro" \
  mysql:8.0.43 --log-bin-trust-function-creators=1

# 2. the application. Flyway runs V9-V12 and V20-V28 on first boot.
SPRING_PROFILES_ACTIVE=container \
DB_PASSWORD=s3pass \
JWT_SECRET=container-only-secret-not-for-any-real-deployment-0123456789 \
mvn spring-boot:run
```

`--log-bin-trust-function-creators=1` is not tuning: MySQL 8 refuses
`CREATE TRIGGER` from a non-`SUPER` account while binary logging is on (ERROR
1419), so V23–V25 fail without it. The same flag is a **production prerequisite**
before V23 deploys — see `db/migration/README.md`.

The context path is `/api`, so the counter is at
`http://localhost:8080/api/recharge/...`.

## A staff user, since there is no sign-up for one

`/customer/register` only ever mints `ROLE_CUSTOMER`
(`CustomerRegistrationService.createCustomerUser`), which is deliberately locked
out of `/recharge/**`. So a counter user has to be inserted:

```sh
HASH=$(htpasswd -nbBC 10 x 'counter-demo-pass-123' | cut -d: -f2)
docker exec s3-counter-db mysql -uCISADM -ps3pass recharge -e "
  INSERT INTO rc_user (USER_NAME, PASSWORD, ROLES, ACCOUNT_STATUS, CREATED_DATE)
  VALUES ('counterstaff', '$HASH', 'ROLE_USER', 'ACTIVE', NOW());"
```

`ROLE_USER` **is** the staff role — there is no `ROLE_STAFF` and one must not be
created (log §15.11). `ACCOUNT_STATUS` must be `ACTIVE` or `APPROVED`;
`AuthenticationController` refuses anything else.

```sh
TOKEN=$(curl -s -X POST http://localhost:8080/api/authenticate \
  -H 'Content-Type: application/json' \
  -d '{"username":"counterstaff","password":"counter-demo-pass-123"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
```

## Driving one sale, which is the slice's own gate

```sh
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/recharge/config

curl -s -X POST localhost:8080/api/recharge/transactions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"recharges":[{"serviceType":"TV","number":"02700000001",
                     "operatorCode":"DISHTV","amount":240.00}],
       "settlementLines":[{"method":"CASH","amount":250.00,"amountTendered":250.00}]}'

curl -s -X POST localhost:8080/api/recharge/recharges/1/complete \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"vendorRef":"A1-DEMO-5512"}'

curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/recharge/day-summary
```

That sale should leave exactly two vouchers, both sealed:

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

## ⚠ The drawer identity, and the mistake it is worth not repeating

```
drawer  =  totalCollected  +  heldPending        where totalCollected = takings + surcharge
```

**Compare the drawer against `totalCollected`, never against `totalTakings`.**
Takings are the recharge FACE VALUE and exclude the shop's own charge, which is
its own income line by decision (log §10.2, §15.11). This slice's own handoff
note, PR and plan all first stated the identity as `takings + held`, which
balances only when every surcharge is zero — and a close screen built on it shows
a phantom shortfall of ₹10 per DTH recharge, every day.

It was found here, by driving one real sale, and not by any test: the unit test
asserting the identity used two MOBILE recharges, where the term vanishes.
`RechargeHistoryServiceTest.drawerIdentityWithSurcharge` now pins both forms —
the right one as an equality, the wrong one as an inequality.

## Throwing it away

```sh
docker rm -f s3-counter-db
```

Nothing persists outside the container, which is the point.
