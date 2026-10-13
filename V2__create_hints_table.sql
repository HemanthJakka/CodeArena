-- Creates the 'hints' table required by com.codearena.entity.Hint.
-- Without this table, Hibernate schema validation fails at startup with:
--   "Schema-validation: missing table [hints]"
-- which then cascades into every other bean failing to load
-- (jwtAuthFilter -> userDetailsServiceImpl -> userRepository -> entityManagerFactory),
-- and the whole backend refuses to start.

CREATE TABLE IF NOT EXISTS hints (
    id BIGINT NOT NULL AUTO_INCREMENT,
    problem_id BIGINT NOT NULL,
    order_index INT NOT NULL DEFAULT 0,
    content VARCHAR(2000) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_hints_problem FOREIGN KEY (problem_id) REFERENCES problems (id) ON DELETE CASCADE
);

SET @idx_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'hints'
      AND index_name = 'idx_hints_problem_id'
);

SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE hints ADD INDEX idx_hints_problem_id (problem_id)',
    'SELECT 1');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
