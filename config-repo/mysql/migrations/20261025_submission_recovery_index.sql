-- Apply once to an existing MySQL database before enabling the recovery scan.
-- Fresh demo databases receive the same index from config-repo/mysql/init.sql.
ALTER TABLE exam_examination_record
    ADD INDEX idx_submit_recovery (submit_status, is_deleted, update_time, id);
