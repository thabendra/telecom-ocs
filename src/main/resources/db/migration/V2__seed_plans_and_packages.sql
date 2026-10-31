INSERT INTO plan (code, name, monthly_fee, data_gb, voice_minutes, max_bandwidth_kbps) VALUES ('BASIC', 'Basic 10GB', 499.00, 10, 100, 10240);
INSERT INTO plan (code, name, monthly_fee, data_gb, voice_minutes, max_bandwidth_kbps) VALUES ('SMART', 'Smart 40GB', 1199.00, 40, 500, 20480);
INSERT INTO plan (code, name, monthly_fee, data_gb, voice_minutes, max_bandwidth_kbps) VALUES ('MAX', 'Max 100GB', 2499.00, 100, 2000, 51200);

-- YouTube Unlimited: 2 Mbps for the first 50GB, then 512 kbps (fair usage policy)
INSERT INTO data_package (code, name, app, price, data_mb, validity_days, max_bandwidth_kbps, fair_usage_mb, throttled_bandwidth_kbps)
VALUES ('YT_UNLIMITED', 'YouTube Unlimited 30 Days', 'YOUTUBE', 690.00, NULL, 30, 2048, 51200, 512);
INSERT INTO data_package (code, name, app, price, data_mb, validity_days, max_bandwidth_kbps)
VALUES ('WA_2GB', 'WhatsApp 2GB 30 Days', 'WHATSAPP', 99.00, 2048, 30, 1024);
INSERT INTO data_package (code, name, app, price, data_mb, validity_days, max_bandwidth_kbps)
VALUES ('FB_5GB', 'Facebook 5GB 30 Days', 'FACEBOOK', 199.00, 5120, 30, 2048);
INSERT INTO data_package (code, name, app, price, data_mb, validity_days, max_bandwidth_kbps)
VALUES ('EXTRA_25GB', 'Extra 25GB Anytime 30 Days', NULL, 1490.00, 25600, 30, 20480);
