-- Capture the source IP from the last heartbeat. Used by the diagnostics endpoint
-- so operators can see where the device is reaching us from (NAT churn, VPN swaps,
-- ISP migrations all show up here). Length 45 covers IPv6 + zone id.
ALTER TABLE device ADD COLUMN last_known_ip VARCHAR(45);
