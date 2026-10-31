-- Moves a subscriber to a new plan, charges the new plan's monthly fee from their balance and refills plan data.
-- The row is locked (FOR UPDATE) so two parallel plan changes can't both spend the same balance.
-- Business rule errors use ORA-20001..20004; the Java layer maps them to SOAP client faults.
CREATE OR REPLACE PROCEDURE change_plan (
    p_msisdn         IN  subscriber.msisdn%TYPE,
    p_new_plan_code  IN  plan.code%TYPE
) AS
    v_subscriber_id  subscriber.id%TYPE;
    v_old_plan_id    subscriber.plan_id%TYPE;
    v_balance        subscriber.balance%TYPE;
    v_status         subscriber.status%TYPE;
    v_new_plan_id    plan.id%TYPE;
    v_fee            plan.monthly_fee%TYPE;
BEGIN
    BEGIN
        SELECT id, plan_id, balance, status
          INTO v_subscriber_id, v_old_plan_id, v_balance, v_status
          FROM subscriber
         WHERE msisdn = p_msisdn
           FOR UPDATE;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20001, 'Subscriber ' || p_msisdn || ' not found');
    END;

    BEGIN
        SELECT id, monthly_fee
          INTO v_new_plan_id, v_fee
          FROM plan
         WHERE code = p_new_plan_code;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20002, 'Plan ' || p_new_plan_code || ' not found');
    END;

    IF v_status <> 'ACTIVE' THEN
        RAISE_APPLICATION_ERROR(-20003, 'Subscriber ' || p_msisdn || ' is not active');
    END IF;

    IF v_new_plan_id = v_old_plan_id THEN
        RAISE_APPLICATION_ERROR(-20003, 'Subscriber is already on plan ' || p_new_plan_code);
    END IF;

    IF v_balance < v_fee THEN
        RAISE_APPLICATION_ERROR(-20004, 'Insufficient balance: ' || v_balance || ', plan fee is ' || v_fee);
    END IF;

    -- new plan starts with its full data allowance
    UPDATE subscriber
       SET plan_id = v_new_plan_id,
           balance = balance - v_fee,
           plan_data_used_mb = 0
     WHERE id = v_subscriber_id;

    INSERT INTO plan_change (subscriber_id, old_plan_id, new_plan_id, fee_charged)
    VALUES (v_subscriber_id, v_old_plan_id, v_new_plan_id, v_fee);
END change_plan;
/
