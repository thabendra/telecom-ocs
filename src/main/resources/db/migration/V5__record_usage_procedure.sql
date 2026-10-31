-- Charges one usage event, the way an online charging system (OCS) does after the gateway's
-- DPI has tagged the traffic with an app (YOUTUBE, WHATSAPP, FACEBOOK or GENERAL).
--
-- Buckets are used in this order, and one event can be split across several of them:
--   1. valid packages for this app (YouTube Unlimited for YOUTUBE traffic), soonest expiring first
--   2. valid add-on packages for any app (Extra 25GB), soonest expiring first
--   3. the base plan's data
--   4. pay as you go from the money balance
-- If even the balance can't cover the rest, nothing is charged (ORA-20005).
--
-- p_bandwidth_kbps returns the speed policy (PCRF) for the bucket that served the end of this event:
-- the package or plan speed, the throttled speed after fair usage, or the pay as you go speed.
CREATE OR REPLACE PROCEDURE record_usage (
    p_msisdn          IN  subscriber.msisdn%TYPE,
    p_app             IN  usage_record.app%TYPE,
    p_data_mb         IN  NUMBER,
    p_usage_ref       IN  usage_record.usage_ref%TYPE,
    p_bandwidth_kbps  OUT NUMBER,
    p_money_charged   OUT NUMBER
) AS
    c_payg_rate_per_mb   CONSTANT NUMBER := 0.50;   -- LKR per MB once all data is used
    c_payg_bandwidth     CONSTANT NUMBER := 1024;   -- kbps on pay as you go

    v_subscriber_id  subscriber.id%TYPE;
    v_status         subscriber.status%TYPE;
    v_balance        subscriber.balance%TYPE;
    v_plan_used_mb   subscriber.plan_data_used_mb%TYPE;
    v_plan_data_mb   NUMBER;
    v_plan_bandwidth plan.max_bandwidth_kbps%TYPE;
    v_remaining      NUMBER := p_data_mb;
    v_take           NUMBER;
    v_cost           NUMBER;
BEGIN
    IF p_data_mb IS NULL OR p_data_mb <= 0 THEN
        RAISE_APPLICATION_ERROR(-20006, 'Usage must be a positive number of MB');
    END IF;

    -- lock the subscriber: usage events for one subscriber are charged one at a time
    BEGIN
        SELECT s.id, s.status, s.balance, s.plan_data_used_mb, p.data_gb * 1024, p.max_bandwidth_kbps
          INTO v_subscriber_id, v_status, v_balance, v_plan_used_mb, v_plan_data_mb, v_plan_bandwidth
          FROM subscriber s
          JOIN plan p ON p.id = s.plan_id
         WHERE s.msisdn = p_msisdn
           FOR UPDATE OF s.balance;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20001, 'Subscriber ' || p_msisdn || ' not found');
    END;

    IF v_status <> 'ACTIVE' THEN
        RAISE_APPLICATION_ERROR(-20003, 'Subscriber ' || p_msisdn || ' is not active');
    END IF;

    p_money_charged := 0;

    -- 1 + 2: app packages first, then add-on packages
    FOR bucket IN (
        SELECT sp.id, sp.data_mb_used, dp.data_mb, dp.max_bandwidth_kbps,
               dp.fair_usage_mb, dp.throttled_bandwidth_kbps
          FROM subscriber_package sp
          JOIN data_package dp ON dp.id = sp.package_id
         WHERE sp.subscriber_id = v_subscriber_id
           AND sp.expires_at > SYSTIMESTAMP
           AND (dp.app = p_app OR dp.app IS NULL)
         ORDER BY CASE WHEN dp.app IS NULL THEN 1 ELSE 0 END, sp.expires_at
    ) LOOP
        EXIT WHEN v_remaining = 0;

        IF bucket.data_mb IS NULL THEN
            v_take := v_remaining;                                        -- unlimited
        ELSE
            v_take := LEAST(v_remaining, bucket.data_mb - bucket.data_mb_used);
        END IF;

        IF v_take > 0 THEN
            UPDATE subscriber_package
               SET data_mb_used = data_mb_used + v_take
             WHERE id = bucket.id;

            INSERT INTO usage_record (usage_ref, subscriber_id, app, source, subscriber_package_id, data_mb)
            VALUES (p_usage_ref, v_subscriber_id, p_app, 'PACKAGE', bucket.id, v_take);

            v_remaining := v_remaining - v_take;

            IF bucket.fair_usage_mb IS NOT NULL AND bucket.data_mb_used + v_take >= bucket.fair_usage_mb THEN
                p_bandwidth_kbps := bucket.throttled_bandwidth_kbps;
            ELSE
                p_bandwidth_kbps := bucket.max_bandwidth_kbps;
            END IF;
        END IF;
    END LOOP;

    -- 3: base plan data
    IF v_remaining > 0 AND v_plan_used_mb < v_plan_data_mb THEN
        v_take := LEAST(v_remaining, v_plan_data_mb - v_plan_used_mb);

        UPDATE subscriber
           SET plan_data_used_mb = plan_data_used_mb + v_take
         WHERE id = v_subscriber_id;

        INSERT INTO usage_record (usage_ref, subscriber_id, app, source, data_mb)
        VALUES (p_usage_ref, v_subscriber_id, p_app, 'PLAN', v_take);

        v_remaining := v_remaining - v_take;
        p_bandwidth_kbps := v_plan_bandwidth;
    END IF;

    -- 4: pay as you go from the balance
    IF v_remaining > 0 THEN
        v_cost := v_remaining * c_payg_rate_per_mb;

        IF v_balance < v_cost THEN
            RAISE_APPLICATION_ERROR(-20005, 'Insufficient data and balance: ' || v_remaining
                || ' MB needs ' || v_cost || ', balance is ' || v_balance);
        END IF;

        UPDATE subscriber
           SET balance = balance - v_cost
         WHERE id = v_subscriber_id;

        INSERT INTO usage_record (usage_ref, subscriber_id, app, source, data_mb, money_charged)
        VALUES (p_usage_ref, v_subscriber_id, p_app, 'BALANCE', v_remaining, v_cost);

        p_money_charged := v_cost;
        p_bandwidth_kbps := c_payg_bandwidth;
    END IF;
END record_usage;
/
