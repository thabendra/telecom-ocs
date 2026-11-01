-- 2000 subscribers for the load tests, so sessions don't wait for each other's row lock.
-- Plenty of balance: a refused charge isn't what is being measured.
MERGE INTO subscriber s
USING (SELECT '947' || LPAD(LEVEL, 8, '0') AS msisdn,
              '2000' || LPAD(LEVEL, 8, '0') AS nic
         FROM dual CONNECT BY LEVEL <= 2000) d
   ON (s.msisdn = d.msisdn)
 WHEN NOT MATCHED THEN
      INSERT (msisdn, full_name, nic, balance, plan_id)
      VALUES (d.msisdn, 'Load Test', d.nic, 100000, (SELECT id FROM plan WHERE code = 'BASIC'));
COMMIT;
SELECT COUNT(*) AS subscribers FROM subscriber;
EXIT;
