-- AUTH-02: POST /api/devices/register is public and keyed only on the serial number, so for an
-- already-registered serial it used to rotate the live device's token for anyone who asked.
-- Re-registration is now refused (409) unless an ADMIN has opened a short window for that device;
-- the next registration claims the window (a conditional UPDATE, so exactly one caller wins) and
-- clears it. NULL = no window open.
ALTER TABLE device ADD COLUMN reregistration_allowed_until TIMESTAMP;
