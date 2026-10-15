#!/usr/bin/env bash
# Stop everything start.sh started and throw the database away.
set -uo pipefail

pkill -f "RetailShopAppApplication" 2>/dev/null
pkill -f "spring-boot:run" 2>/dev/null
pkill -f "ng serve" 2>/dev/null
pkill -f "angular/build/src/builders" 2>/dev/null
docker rm -f s3-counter-db >/dev/null 2>&1

echo "Stopped. Nothing persists — the database is gone, which is the point."
