-- Group 8: charging correctness.
--
--   CH-7  a session belongs to the gateway that opened it; update / release from another gateway are
--         refused (ORA-20012), checked under the subscriber lock like everything else in CHARGING
--   CH-8  sessions with no update for longer than the grant validity are expired: their reservation
--         goes back to the bucket, nothing is charged (no usage was reported), and audit says EXPIRED
--   end_reason says how a session ended: RELEASE (by the gateway), DENY (nothing left at start), EXPIRED

ALTER TABLE charging_session ADD (
    gateway     VARCHAR2(64),            -- certificate name of the gateway that opened it (NULL: before V11)
    end_reason  VARCHAR2(10) CONSTRAINT chk_charging_session_end CHECK (end_reason IN ('RELEASE', 'DENY', 'EXPIRED'))
);

-- the expiry run looks for open sessions by age
CREATE INDEX ix_charging_session_open ON charging_session (status, updated_at);

-- the audit trail gets EXPIRED; V10 left its CHECK unnamed, so find it by its condition and replace it
BEGIN
    FOR c IN (SELECT constraint_name
                FROM user_constraints
               WHERE table_name = 'AUDIT_EVENT'
                 AND constraint_type = 'C'
                 AND search_condition_vc LIKE '%''GRANT''%') LOOP
        EXECUTE IMMEDIATE 'ALTER TABLE audit_event DROP CONSTRAINT ' || c.constraint_name;
    END LOOP;
END;
/

ALTER TABLE audit_event ADD CONSTRAINT chk_audit_event_event CHECK (event IN
    ('GRANT', 'DENY', 'BLOCK', 'UNBLOCK', 'ACKED', 'FAILED', 'SUSPEND', 'UNSUSPEND', 'EXPIRED'));

-- The CHARGING package as in V10, plus the owner check, end_reason and expire_stale_sessions.
CREATE OR REPLACE PACKAGE charging AS

    PROCEDURE start_session (
        p_session_id      IN  charging_session.id%TYPE,
        p_msisdn          IN  subscriber.msisdn%TYPE,
        p_app             IN  charging_session.app%TYPE,
        p_requested_mb    IN  NUMBER,
        p_granted_mb      OUT NUMBER,
        p_bandwidth_kbps  OUT NUMBER,
        p_source          OUT VARCHAR2,
        p_package_code    OUT VARCHAR2
    );

    PROCEDURE update_session (
        p_session_id      IN  charging_session.id%TYPE,
        p_request_number  IN  NUMBER,
        p_used_mb         IN  NUMBER,
        p_requested_mb    IN  NUMBER,
        p_granted_mb      OUT NUMBER,
        p_bandwidth_kbps  OUT NUMBER,
        p_source          OUT VARCHAR2,
        p_package_code    OUT VARCHAR2
    );

    PROCEDURE release_session (
        p_session_id      IN  charging_session.id%TYPE,
        p_request_number  IN  NUMBER,
        p_used_mb         IN  NUMBER,
        p_total_used_mb   OUT NUMBER
    );

    -- CH-8: frees the reservations of sessions with no update for p_idle_seconds, charging nothing
    -- (no usage was reported for them), and closes them. Returns how many it closed.
    PROCEDURE expire_stale_sessions (
        p_idle_seconds    IN  NUMBER,
        p_expired         OUT NUMBER
    );

END charging;
/

CREATE OR REPLACE PACKAGE BODY charging AS

    c_payg_rate_per_mb  CONSTANT NUMBER := 0.50;   -- LKR per MB once all data is used
    c_payg_bandwidth    CONSTANT NUMBER := 1024;   -- kbps on pay as you go

    PROCEDURE check_mb (p_mb IN NUMBER, p_name IN VARCHAR2, p_allow_zero IN BOOLEAN) IS
    BEGIN
        IF p_mb IS NULL OR p_mb < 0 OR (p_mb = 0 AND NOT p_allow_zero) OR p_mb <> TRUNC(p_mb) THEN
            RAISE_APPLICATION_ERROR(-20006, p_name || ' must be a whole number of MB'
                || CASE WHEN p_allow_zero THEN ' (0 or more)' ELSE ' (1 or more)' END);
        END IF;
    END check_mb;

    -- OB-1: one audit row per charging decision. The gateway is the Oracle client identifier the
    -- backend set for this call (DBMS_SESSION.SET_IDENTIFIER, see AuditContext). A request for 0 MB
    -- (the gateway's last report) asks for nothing, so it is no decision and isn't recorded.
    PROCEDURE audit_decision (
        p_session_id      IN  charging_session.id%TYPE,
        p_subscriber_id   IN  subscriber.id%TYPE,
        p_requested_mb    IN  NUMBER,
        p_granted_mb      IN  NUMBER,
        p_bandwidth_kbps  IN  NUMBER,
        p_source          IN  VARCHAR2,
        p_package_code    IN  VARCHAR2
    ) IS
        v_msisdn  subscriber.msisdn%TYPE;
    BEGIN
        IF p_requested_mb = 0 THEN
            RETURN;
        END IF;
        SELECT msisdn INTO v_msisdn FROM subscriber WHERE id = p_subscriber_id;
        audit_log.log_event(
            p_event          => CASE WHEN p_granted_mb > 0 THEN 'GRANT' ELSE 'DENY' END,
            p_msisdn         => v_msisdn,
            p_gateway        => SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER'),
            p_session_id     => p_session_id,
            p_requested_mb   => p_requested_mb,
            p_granted_mb     => p_granted_mb,
            p_bandwidth_kbps => CASE WHEN p_granted_mb > 0 THEN p_bandwidth_kbps END,
            p_source         => CASE WHEN p_granted_mb > 0 THEN p_source END,
            p_package_code   => p_package_code);
    END audit_decision;

    -- Locks the session's subscriber row, then checks the session belongs to the calling gateway
    -- (CH-7) and is still open. Locking before reading the status means two releases of one session
    -- can't both pass. Another gateway's session answers exactly like a session that doesn't exist
    -- (ORA-20012, same message as ORA-20007), so a gateway can't even learn which ids are in use.
    PROCEDURE lock_open_session (
        p_session_id     IN  charging_session.id%TYPE,
        p_subscriber_id  OUT subscriber.id%TYPE,
        p_app            OUT charging_session.app%TYPE
    ) IS
        v_status   charging_session.status%TYPE;
        v_gateway  charging_session.gateway%TYPE;
    BEGIN
        BEGIN
            SELECT subscriber_id INTO p_subscriber_id FROM charging_session WHERE id = p_session_id;
        EXCEPTION
            WHEN NO_DATA_FOUND THEN
                RAISE_APPLICATION_ERROR(-20007, 'Charging session ' || p_session_id || ' not found');
        END;

        SELECT id INTO p_subscriber_id FROM subscriber WHERE id = p_subscriber_id FOR UPDATE;

        SELECT app, status, gateway INTO p_app, v_status, v_gateway FROM charging_session WHERE id = p_session_id;
        -- sessions from before V11 have no gateway: anyone allowed may go on with them
        IF v_gateway IS NOT NULL AND v_gateway <> NVL(SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER'), '-') THEN
            RAISE_APPLICATION_ERROR(-20012, 'Charging session ' || p_session_id || ' not found');
        END IF;
        IF v_status <> 'OPEN' THEN
            RAISE_APPLICATION_ERROR(-20008, 'Charging session ' || p_session_id || ' is already released');
        END IF;
    END lock_open_session;

    -- Records the request number; a number already used in this session means the gateway sent the
    -- same request again, so it is refused before anything is charged.
    PROCEDURE register_request (
        p_session_id      IN  charging_session.id%TYPE,
        p_request_number  IN  NUMBER,
        p_request_type    IN  VARCHAR2
    ) IS
    BEGIN
        IF p_request_number IS NULL OR p_request_number < 1 OR p_request_number <> TRUNC(p_request_number) THEN
            RAISE_APPLICATION_ERROR(-20006, 'Request number must be a whole number (1 or more)');
        END IF;

        INSERT INTO charging_request (session_id, request_number, request_type)
        VALUES (p_session_id, p_request_number, p_request_type);
    EXCEPTION
        WHEN DUP_VAL_ON_INDEX THEN
            RAISE_APPLICATION_ERROR(-20009, 'Request ' || p_request_number || ' of charging session '
                || p_session_id || ' was already processed');
    END register_request;

    -- Charges the used part of the session's active grant and frees the rest of it.
    -- The gateway never lets traffic go past a grant, so a report above the grant is capped at the grant.
    PROCEDURE commit_active (
        p_session_id     IN  charging_session.id%TYPE,
        p_subscriber_id  IN  subscriber.id%TYPE,
        p_app            IN  charging_session.app%TYPE,
        p_used_mb        IN  NUMBER
    ) IS
        v_id        quota_reservation.id%TYPE;
        v_source    quota_reservation.source%TYPE;
        v_pkg_id    quota_reservation.subscriber_package_id%TYPE;
        v_reserved  quota_reservation.reserved_mb%TYPE;
        v_money_rsv quota_reservation.money_reserved%TYPE;
        v_used      NUMBER;
        v_money     NUMBER := 0;
    BEGIN
        BEGIN
            SELECT id, source, subscriber_package_id, reserved_mb, money_reserved
              INTO v_id, v_source, v_pkg_id, v_reserved, v_money_rsv
              FROM quota_reservation
             WHERE session_id = p_session_id
               AND status = 'ACTIVE';
        EXCEPTION
            WHEN NO_DATA_FOUND THEN
                RETURN;   -- last request was denied, nothing is held
        END;

        v_used := LEAST(p_used_mb, v_reserved);

        IF v_source = 'PACKAGE' THEN
            UPDATE subscriber_package
               SET data_mb_reserved = data_mb_reserved - v_reserved,
                   data_mb_used     = data_mb_used + v_used
             WHERE id = v_pkg_id;
        ELSIF v_source = 'PLAN' THEN
            UPDATE subscriber
               SET plan_data_reserved_mb = plan_data_reserved_mb - v_reserved,
                   plan_data_used_mb     = plan_data_used_mb + v_used
             WHERE id = p_subscriber_id;
        ELSE
            v_money := v_used * c_payg_rate_per_mb;
            UPDATE subscriber
               SET balance_reserved = balance_reserved - v_money_rsv,
                   balance          = balance - v_money
             WHERE id = p_subscriber_id;
        END IF;

        IF v_used > 0 THEN
            INSERT INTO usage_record (usage_ref, subscriber_id, app, source, subscriber_package_id, data_mb, money_charged)
            VALUES (p_session_id, p_subscriber_id, p_app, v_source, v_pkg_id, v_used, v_money);
        END IF;

        UPDATE quota_reservation
           SET status = 'COMMITTED', used_mb = v_used, committed_at = SYSTIMESTAMP
         WHERE id = v_id;

        UPDATE charging_session
           SET total_used_mb = total_used_mb + v_used, updated_at = SYSTIMESTAMP
         WHERE id = p_session_id;
    END commit_active;

    -- Holds up to p_requested_mb from the first bucket that has anything left.
    PROCEDURE reserve (
        p_session_id      IN  charging_session.id%TYPE,
        p_subscriber_id   IN  subscriber.id%TYPE,
        p_app             IN  charging_session.app%TYPE,
        p_requested_mb    IN  NUMBER,
        p_granted_mb      OUT NUMBER,
        p_bandwidth_kbps  OUT NUMBER,
        p_source          OUT VARCHAR2,
        p_package_code    OUT VARCHAR2
    ) IS
        v_take       NUMBER;
        v_plan_left  NUMBER;
        v_plan_speed plan.max_bandwidth_kbps%TYPE;
        v_free_money NUMBER;
    BEGIN
        p_granted_mb := 0;

        IF p_requested_mb = 0 THEN
            RETURN;
        END IF;

        -- 1 + 2: package for this app first, then add-on packages
        FOR bucket IN (
            SELECT sp.id, sp.data_mb_used, sp.data_mb_reserved, dp.code, dp.data_mb,
                   dp.max_bandwidth_kbps, dp.fair_usage_mb, dp.throttled_bandwidth_kbps
              FROM subscriber_package sp
              JOIN data_package dp ON dp.id = sp.package_id
             WHERE sp.subscriber_id = p_subscriber_id
               AND sp.expires_at > SYSTIMESTAMP
               AND (dp.app = p_app OR dp.app IS NULL)
             ORDER BY CASE WHEN dp.app IS NULL THEN 1 ELSE 0 END, sp.expires_at
        ) LOOP
            IF bucket.data_mb IS NULL THEN
                v_take := p_requested_mb;                                   -- unlimited
            ELSE
                v_take := LEAST(p_requested_mb, bucket.data_mb - bucket.data_mb_used - bucket.data_mb_reserved);
            END IF;

            IF v_take > 0 THEN
                UPDATE subscriber_package
                   SET data_mb_reserved = data_mb_reserved + v_take
                 WHERE id = bucket.id;

                INSERT INTO quota_reservation (session_id, source, subscriber_package_id, reserved_mb)
                VALUES (p_session_id, 'PACKAGE', bucket.id, v_take);

                p_granted_mb   := v_take;
                p_source       := 'PACKAGE';
                p_package_code := bucket.code;
                IF bucket.fair_usage_mb IS NOT NULL AND bucket.data_mb_used >= bucket.fair_usage_mb THEN
                    p_bandwidth_kbps := bucket.throttled_bandwidth_kbps;
                ELSE
                    p_bandwidth_kbps := bucket.max_bandwidth_kbps;
                END IF;
                RETURN;
            END IF;
        END LOOP;

        SELECT p.data_gb * 1024 - s.plan_data_used_mb - s.plan_data_reserved_mb,
               p.max_bandwidth_kbps,
               s.balance - s.balance_reserved
          INTO v_plan_left, v_plan_speed, v_free_money
          FROM subscriber s
          JOIN plan p ON p.id = s.plan_id
         WHERE s.id = p_subscriber_id;

        -- 3: plan data
        v_take := LEAST(p_requested_mb, v_plan_left);
        IF v_take > 0 THEN
            UPDATE subscriber
               SET plan_data_reserved_mb = plan_data_reserved_mb + v_take
             WHERE id = p_subscriber_id;

            INSERT INTO quota_reservation (session_id, source, reserved_mb)
            VALUES (p_session_id, 'PLAN', v_take);

            p_granted_mb     := v_take;
            p_source         := 'PLAN';
            p_bandwidth_kbps := v_plan_speed;
            RETURN;
        END IF;

        -- 4: pay as you go, holding the money for the MB granted
        v_take := LEAST(p_requested_mb, FLOOR(v_free_money / c_payg_rate_per_mb));
        IF v_take > 0 THEN
            UPDATE subscriber
               SET balance_reserved = balance_reserved + v_take * c_payg_rate_per_mb
             WHERE id = p_subscriber_id;

            INSERT INTO quota_reservation (session_id, source, reserved_mb, money_reserved)
            VALUES (p_session_id, 'BALANCE', v_take, v_take * c_payg_rate_per_mb);

            p_granted_mb     := v_take;
            p_source         := 'BALANCE';
            p_bandwidth_kbps := c_payg_bandwidth;
        END IF;
        -- otherwise p_granted_mb stays 0: DENY
    END reserve;

    PROCEDURE start_session (
        p_session_id      IN  charging_session.id%TYPE,
        p_msisdn          IN  subscriber.msisdn%TYPE,
        p_app             IN  charging_session.app%TYPE,
        p_requested_mb    IN  NUMBER,
        p_granted_mb      OUT NUMBER,
        p_bandwidth_kbps  OUT NUMBER,
        p_source          OUT VARCHAR2,
        p_package_code    OUT VARCHAR2
    ) IS
        v_subscriber_id  subscriber.id%TYPE;
        v_status         subscriber.status%TYPE;
    BEGIN
        check_mb(p_requested_mb, 'Requested data', FALSE);

        BEGIN
            SELECT id, status INTO v_subscriber_id, v_status
              FROM subscriber
             WHERE msisdn = p_msisdn
               FOR UPDATE;
        EXCEPTION
            WHEN NO_DATA_FOUND THEN
                RAISE_APPLICATION_ERROR(-20001, 'Subscriber ' || p_msisdn || ' not found');
        END;

        IF v_status <> 'ACTIVE' THEN
            RAISE_APPLICATION_ERROR(-20003, 'Subscriber ' || p_msisdn || ' is not active');
        END IF;

        -- CH-7: the session belongs to the gateway that opened it (the client identifier the backend set)
        INSERT INTO charging_session (id, subscriber_id, app, gateway)
        VALUES (p_session_id, v_subscriber_id, p_app, SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER'));

        INSERT INTO charging_request (session_id, request_number, request_type)
        VALUES (p_session_id, 0, 'START');

        reserve(p_session_id, v_subscriber_id, p_app, p_requested_mb,
                p_granted_mb, p_bandwidth_kbps, p_source, p_package_code);

        audit_decision(p_session_id, v_subscriber_id, p_requested_mb,
                       p_granted_mb, p_bandwidth_kbps, p_source, p_package_code);

        -- denied on the first request: nothing to track, the gateway blocks straight away
        IF p_granted_mb = 0 THEN
            UPDATE charging_session
               SET status = 'RELEASED', released_at = SYSTIMESTAMP, end_reason = 'DENY'
             WHERE id = p_session_id;
        END IF;
    END start_session;

    PROCEDURE update_session (
        p_session_id      IN  charging_session.id%TYPE,
        p_request_number  IN  NUMBER,
        p_used_mb         IN  NUMBER,
        p_requested_mb    IN  NUMBER,
        p_granted_mb      OUT NUMBER,
        p_bandwidth_kbps  OUT NUMBER,
        p_source          OUT VARCHAR2,
        p_package_code    OUT VARCHAR2
    ) IS
        v_subscriber_id  subscriber.id%TYPE;
        v_app            charging_session.app%TYPE;
    BEGIN
        check_mb(p_used_mb, 'Used data', TRUE);
        check_mb(p_requested_mb, 'Requested data', TRUE);

        lock_open_session(p_session_id, v_subscriber_id, v_app);
        register_request(p_session_id, p_request_number, 'UPDATE');
        commit_active(p_session_id, v_subscriber_id, v_app, p_used_mb);
        reserve(p_session_id, v_subscriber_id, v_app, p_requested_mb,
                p_granted_mb, p_bandwidth_kbps, p_source, p_package_code);
        audit_decision(p_session_id, v_subscriber_id, p_requested_mb,
                       p_granted_mb, p_bandwidth_kbps, p_source, p_package_code);
    END update_session;

    PROCEDURE release_session (
        p_session_id      IN  charging_session.id%TYPE,
        p_request_number  IN  NUMBER,
        p_used_mb         IN  NUMBER,
        p_total_used_mb   OUT NUMBER
    ) IS
        v_subscriber_id  subscriber.id%TYPE;
        v_app            charging_session.app%TYPE;
    BEGIN
        check_mb(p_used_mb, 'Used data', TRUE);

        lock_open_session(p_session_id, v_subscriber_id, v_app);
        register_request(p_session_id, p_request_number, 'RELEASE');
        commit_active(p_session_id, v_subscriber_id, v_app, p_used_mb);

        UPDATE charging_session
           SET status = 'RELEASED', released_at = SYSTIMESTAMP, updated_at = SYSTIMESTAMP, end_reason = 'RELEASE'
         WHERE id = p_session_id
        RETURNING total_used_mb INTO p_total_used_mb;
    END release_session;

    PROCEDURE expire_stale_sessions (
        p_idle_seconds    IN  NUMBER,
        p_expired         OUT NUMBER
    ) IS
        -- updated_at is a plain TIMESTAMP holding the database's wall clock (SYSTIMESTAMP stored without
        -- its zone), so compare with the same wall clock, never with SYSTIMESTAMP itself: that would
        -- convert updated_at with the session's time zone first (the +05:30 lesson of group 5)
        v_cutoff   TIMESTAMP := CAST(SYSTIMESTAMP AS TIMESTAMP) - NUMTODSINTERVAL(p_idle_seconds, 'SECOND');
        v_locked   NUMBER;
        v_still    NUMBER;
        v_msisdn   subscriber.msisdn%TYPE;
    BEGIN
        p_expired := 0;
        FOR s IN (SELECT id, subscriber_id, app, gateway
                    FROM charging_session
                   WHERE status = 'OPEN' AND updated_at < v_cutoff
                   ORDER BY updated_at
                   FETCH FIRST 500 ROWS ONLY) LOOP
            -- the same lock every charging call takes; a subscriber being charged right now is skipped
            -- and picked up by the next run, so this never waits behind live traffic
            BEGIN
                SELECT id INTO v_locked FROM subscriber WHERE id = s.subscriber_id FOR UPDATE SKIP LOCKED;
            EXCEPTION
                WHEN NO_DATA_FOUND THEN
                    v_locked := NULL;
            END;

            IF v_locked IS NOT NULL THEN
                -- an update may have come in between the query and the lock: look again under the lock
                SELECT COUNT(*) INTO v_still
                  FROM charging_session
                 WHERE id = s.id AND status = 'OPEN' AND updated_at < v_cutoff;

                IF v_still = 1 THEN
                    commit_active(s.id, s.subscriber_id, s.app, 0);   -- frees the hold, charges nothing
                    UPDATE charging_session
                       SET status = 'RELEASED', released_at = SYSTIMESTAMP, end_reason = 'EXPIRED'
                     WHERE id = s.id;
                    SELECT msisdn INTO v_msisdn FROM subscriber WHERE id = s.subscriber_id;
                    audit_log.log_event(
                        p_event      => 'EXPIRED',
                        p_msisdn     => v_msisdn,
                        p_gateway    => s.gateway,
                        p_actor      => 'system',
                        p_session_id => s.id);
                    p_expired := p_expired + 1;
                END IF;
            END IF;
        END LOOP;
    END expire_stale_sessions;

END charging;
/
