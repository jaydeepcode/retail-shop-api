-- ============================================================================
-- V33: The booking's idempotency key
-- ============================================================================
-- ⚠ A THIRD MIGRATION THIS SLICE DID NOT PLAN, taken deliberately and with the
-- owner's decision (2026-10-25), because next-session-s4.md says the cap "moves
-- 30 -> 32 with this slice's two migrations and no further". Recorded here
-- rather than in a commit message alone, per OWNER-GATE.md: raising the cap is a
-- deliberate act and never a side effect of adding a file.
--
-- WHAT IT OVERTURNS AND WHY THAT IS SAFE. The cap's stated purpose is keeping
-- V13-V19 out -- "V14 alone adds a CHECK that breaks the legacy app's next UPI
-- write". V13-V19 are BELOW this version and do not exist as files, so moving
-- the cap to 33 cannot admit them. What the cap also does is stop a new file
-- being applied by accident, and that protection is unchanged: 33 is as
-- deliberate as 32 was.
--
-- WHY A COLUMN AT ALL. design-ticket-booking.md §4 requires POST
-- /tickets/bookings to replay a repeated Idempotency-Key as 200 with the
-- original booking, which needs a client-key -> booking-id mapping.
--
--   * rc_idempotency cannot hold it. PAYMENT_ID is MEDIUMINT and named for a
--     payment; a BOOKING_ID is BIGINT. Writing one into the other is the exact
--     INT/Long class of mistake S3 spent a session removing, with a wrong column
--     name on top.
--   * The ledger's own idempotency cannot cover it. Its key is derived from the
--     booking id, so a retried POST creates a NEW row with a NEW id and a NEW
--     key -- it deduplicates a repost of the SAME booking, not a client retry.
--
-- So the booking carries its own key. No cross-module coupling, no width
-- mismatch, and the unique index is what makes insert-then-recover work.
--
-- WHAT IT PROTECTS. The server commits the booking and posts its voucher, and
-- the response is then lost -- a blip on the shop LAN, which this app is
-- expected to run over plain HTTP from another machine. The staff member sees a
-- spinner that never resolves and presses again. Without this, that is two
-- bookings and two SALES vouchers: the ledger says 970.00 was taken and the
-- drawer holds 485.00, silently until the evening count -- and CASH_TICKET
-- shares POOL-1 with CASH_RECHARGE, so the variance has two businesses to be
-- attributed between. There is no DELETE endpoint (§4), so undoing it is FRD
-- §8.3 correction work.
--
-- ⚠ APPEND-ONLY from the moment this is applied anywhere. See V31's header.
-- ============================================================================

SELECT 'V33: adding the booking idempotency key...' AS Status;

-- NULLABLE, because a booking written by any path other than endpoint #1 has no
-- client key to record and must not be forced to invent one. The UNIQUE index
-- ignores NULLs in MySQL, so any number of such rows coexist -- which is the
-- behaviour wanted, not a loophole: uniqueness is a claim about keys that were
-- supplied, not about bookings.
ALTER TABLE tkt_booking
    ADD COLUMN IDEMPOTENCY_KEY VARCHAR(80) NULL AFTER NOTE;

-- The constraint that does the work. Insert-then-recover relies on the DATABASE
-- refusing the second insert; a SELECT-then-insert pre-check is a race between
-- two requests that both read "not present" before either writes.
ALTER TABLE tkt_booking
    ADD CONSTRAINT uk_tkt_idem UNIQUE (IDEMPOTENCY_KEY);

SELECT 'V33: complete.' AS Status;
