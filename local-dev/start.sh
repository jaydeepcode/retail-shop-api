#!/usr/bin/env bash
#
# Start the counter — database, backend and the screens — against a throwaway
# database. Nothing here can touch the developer restore.
#
#   ./local-dev/start.sh
#
# Then open http://localhost:4200 and sign in as the user it prints.
# Stop everything with ./local-dev/stop.sh
set -euo pipefail

BACKEND="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB="${SHOP_WEB:-$(cd "$BACKEND/../shop-web" 2>/dev/null && pwd || true)}"
LOGS="$BACKEND/local-dev/logs"
mkdir -p "$LOGS"

USERNAME="${COUNTER_USER:-counterstaff}"
PASSWORD="${COUNTER_PASS:-counter123}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- docker
if ! docker info >/dev/null 2>&1; then
  say "Docker is not reachable. Starting Colima…"
  colima start
fi

# ---------------------------------------------------------------- database
say "1/4  Database (throwaway, port 3307 — the restore on 3306 is untouched)"
docker rm -f s3-counter-db >/dev/null 2>&1 || true
docker run -d --name s3-counter-db \
  -e MYSQL_ROOT_PASSWORD=s3root -e MYSQL_DATABASE=recharge \
  -e MYSQL_USER=CISADM -e MYSQL_PASSWORD=s3pass \
  -p 3307:3306 \
  -v "$BACKEND/src/test/resources/db/legacy-baseline.sql:/docker-entrypoint-initdb.d/01-baseline.sql:ro" \
  -v "$BACKEND/local-dev/01-pre-flyway-remainder.sql:/docker-entrypoint-initdb.d/02-remainder.sql:ro" \
  mysql:8.0.43 --log-bin-trust-function-creators=1 >/dev/null
printf '     waiting'
until docker exec s3-counter-db mysqladmin ping -uCISADM -ps3pass --silent 2>/dev/null | grep -q alive; do
  printf '.'; sleep 2
done
echo ' ready'

# ---------------------------------------------------------------- backend
say "2/4  Backend (Flyway applies V9-V12 and V20-V28, capped at 28)"
cd "$BACKEND"
SPRING_PROFILES_ACTIVE=container \
DB_PASSWORD=s3pass \
JWT_SECRET=container-only-secret-not-for-any-real-deployment-0123456789 \
  nohup mvn -q spring-boot:run > "$LOGS/backend.log" 2>&1 &
printf '     waiting'
until grep -q "Started RetailShopAppApplication" "$LOGS/backend.log" 2>/dev/null; do
  if grep -q "run failed\|APPLICATION FAILED" "$LOGS/backend.log" 2>/dev/null; then
    echo; echo "Backend failed to start. Last lines of $LOGS/backend.log:"; tail -25 "$LOGS/backend.log"; exit 1
  fi
  printf '.'; sleep 2
done
echo ' up on http://localhost:8080/api'

# ---------------------------------------------------------------- staff user
# /customer/register only ever mints ROLE_CUSTOMER, which is deliberately locked
# out of /recharge/**, so a counter user has to be inserted. ROLE_USER IS the
# staff role (log §15.11) — there is no ROLE_STAFF and one must not be created.
say "3/4  Staff login"
HASH=$(htpasswd -nbBC 10 x "$PASSWORD" | cut -d: -f2)
docker exec s3-counter-db mysql -uCISADM -ps3pass recharge -e "
  INSERT INTO rc_user (USER_NAME, PASSWORD, ROLES, ACCOUNT_STATUS, CREATED_DATE)
  VALUES ('$USERNAME', '$HASH', 'ROLE_ADMIN|ROLE_USER', 'ACTIVE', NOW());" 2>/dev/null
echo "     $USERNAME / $PASSWORD"

# ---------------------------------------------------------------- screens
if [ -n "$WEB" ] && [ -d "$WEB" ]; then
  say "4/4  Screens"
  cd "$WEB"
  [ -d node_modules ] || { echo "     installing dependencies, this takes a minute…"; npm install --silent; }
  nohup npx ng serve --port 4200 > "$LOGS/web.log" 2>&1 &
  printf '     waiting'
  until grep -qE "Watch mode enabled|bundle generation complete" "$LOGS/web.log" 2>/dev/null; do
    printf '.'; sleep 3
  done
  echo ' up'
  say "Open http://localhost:4200  —  sign in as $USERNAME / $PASSWORD"
  echo "The counter is under the Recharge tile. Day's end & accounts has the close screen."
else
  say "4/4  Screens — SKIPPED"
  echo "     shop-web not found next to this repo. Set SHOP_WEB=/path/to/shop-web and re-run,"
  echo "     or start it yourself with: cd shop-web && npx ng serve"
fi

echo
echo "Logs:  $LOGS/backend.log   $LOGS/web.log"
echo "Stop:  ./local-dev/stop.sh"
