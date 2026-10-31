-- Buys a data package for a subscriber: charges its price from the balance and makes it valid
-- for validity_days. A subscriber can hold several packages at once (YouTube + WhatsApp + Extra 25GB).
-- Errors: ORA-20001 subscriber not found, ORA-20002 package not found,
--         ORA-20003 subscriber not active, ORA-20004 insufficient balance.
CREATE OR REPLACE PROCEDURE activate_package (
    p_msisdn                 IN  subscriber.msisdn%TYPE,
    p_package_code           IN  data_package.code%TYPE,
    p_subscriber_package_id  OUT subscriber_package.id%TYPE
) AS
    v_subscriber_id  subscriber.id%TYPE;
    v_balance        subscriber.balance%TYPE;
    v_status         subscriber.status%TYPE;
    v_package_id     data_package.id%TYPE;
    v_price          data_package.price%TYPE;
    v_validity_days  data_package.validity_days%TYPE;
BEGIN
    BEGIN
        SELECT id, balance, status
          INTO v_subscriber_id, v_balance, v_status
          FROM subscriber
         WHERE msisdn = p_msisdn
           FOR UPDATE;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20001, 'Subscriber ' || p_msisdn || ' not found');
    END;

    BEGIN
        SELECT id, price, validity_days
          INTO v_package_id, v_price, v_validity_days
          FROM data_package
         WHERE code = p_package_code;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20002, 'Package ' || p_package_code || ' not found');
    END;

    IF v_status <> 'ACTIVE' THEN
        RAISE_APPLICATION_ERROR(-20003, 'Subscriber ' || p_msisdn || ' is not active');
    END IF;

    IF v_balance < v_price THEN
        RAISE_APPLICATION_ERROR(-20004, 'Insufficient balance: ' || v_balance || ', package price is ' || v_price);
    END IF;

    UPDATE subscriber
       SET balance = balance - v_price
     WHERE id = v_subscriber_id;

    INSERT INTO subscriber_package (subscriber_id, package_id, expires_at)
    VALUES (v_subscriber_id, v_package_id, SYSTIMESTAMP + NUMTODSINTERVAL(v_validity_days, 'DAY'))
    RETURNING id INTO p_subscriber_package_id;
END activate_package;
/
