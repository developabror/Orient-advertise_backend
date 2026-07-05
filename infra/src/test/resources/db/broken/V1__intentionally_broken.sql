-- This migration is intentionally broken to test failure handling
CREATE TABLE this_will_work (id BIGINT PRIMARY KEY);
INSERT INTO nonexistent_table (id) VALUES (1);
