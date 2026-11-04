CREATE TABLE pipeline_run (
  id UUID PRIMARY KEY,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  owner_instance VARCHAR(128),
  lease_epoch BIGINT NOT NULL DEFAULT 0,
  lease_until TIMESTAMP(6) WITH TIME ZONE,
  wakeup_requested BOOLEAN NOT NULL DEFAULT FALSE,
  event_seq BIGINT NOT NULL DEFAULT 0,
  meta JSONB
);

-- README §10: month-partitioned, partition key in the PK, plus a default partition
CREATE TABLE run_event (
  run_id UUID NOT NULL,
  seq BIGINT NOT NULL,
  occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  details JSONB,
  PRIMARY KEY (run_id, seq, occurred_at)
) PARTITION BY RANGE (occurred_at);

DO $$
DECLARE m DATE := date_trunc('month', now())::date;
BEGIN
  EXECUTE format('CREATE TABLE run_event_%s PARTITION OF run_event FOR VALUES FROM (%L) TO (%L)',
                 to_char(m, 'YYYYMM'), m, (m + INTERVAL '1 month')::date);
  EXECUTE format('CREATE TABLE run_event_%s PARTITION OF run_event FOR VALUES FROM (%L) TO (%L)',
                 to_char(m + INTERVAL '1 month', 'YYYYMM'), (m + INTERVAL '1 month')::date, (m + INTERVAL '2 month')::date);
END $$;
CREATE TABLE run_event_default PARTITION OF run_event DEFAULT;

-- US-7.1 grant model: the application role can append to run_event but never rewrite it
GRANT SELECT, INSERT, UPDATE ON pipeline_run TO apiorch_app;
GRANT SELECT, INSERT ON run_event TO apiorch_app;
