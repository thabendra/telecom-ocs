-- Push channel outbox (PU-1..PU-4): commands for the network gateways, written in the same transaction
-- as the change that causes them (a DENY, a suspension, a top-up), so a command is never lost or sent
-- for a change that was rolled back. One row per gateway: each gateway must apply it.
--
--   PENDING  waiting for the gateway's next poll
--   SENT     delivered, waiting for the ack; delivered again after next_attempt_at
--   ACKED    the gateway confirmed it
--   FAILED   not confirmed after the last attempt (dead letter, for operations to look at)
--
-- Times keep their time zone: they are compared with SYSTIMESTAMP, and a plain TIMESTAMP would be
-- read in the JDBC session's zone (e.g. +05:30) while SYSTIMESTAMP is the database's (UTC).

CREATE TABLE gateway_command (
    id              VARCHAR2(36)  PRIMARY KEY,                -- also the gateway's idempotency key
    gateway         VARCHAR2(64)  NOT NULL,                   -- certificate name, e.g. pgw-colombo-01
    msisdn          VARCHAR2(11)  NOT NULL,
    action          VARCHAR2(10)  NOT NULL CHECK (action IN ('BLOCK', 'UNBLOCK')),
    reason          VARCHAR2(20)  NOT NULL
                    CHECK (reason IN ('OUT_OF_QUOTA', 'SUSPENDED', 'FRAUD', 'TOP_UP', 'PACKAGE', 'UNSUSPENDED')),
    bandwidth_kbps  NUMBER(10),                               -- UNBLOCK: the speed to allow again
    status          VARCHAR2(10)  DEFAULT 'PENDING' NOT NULL
                    CHECK (status IN ('PENDING', 'SENT', 'ACKED', 'FAILED')),
    attempts        NUMBER(3)     DEFAULT 0 NOT NULL CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    acked_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_gateway_command_speed CHECK (action = 'UNBLOCK' OR bandwidth_kbps IS NULL)
);

-- a gateway's poll: its due commands, oldest first
CREATE INDEX ix_gateway_command_due ON gateway_command (gateway, status, next_attempt_at);
-- the latest command for a subscriber (is it blocked, and why?)
CREATE INDEX ix_gateway_command_msisdn ON gateway_command (msisdn, created_at);
