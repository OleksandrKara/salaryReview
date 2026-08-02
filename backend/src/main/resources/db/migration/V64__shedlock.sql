-- ShedLock's standard schema (see SchedulerLockConfig) — lets @SchedulerLock-annotated methods
-- (and RevenueSnapshotScheduler's manually-locked cron tasks) coordinate across the two backend
-- replicas (blue/green, see docker-compose.yml) so only one of them actually runs a given
-- scheduled job at a time, instead of both racing the same due rows.
CREATE TABLE shedlock (
    name       VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
