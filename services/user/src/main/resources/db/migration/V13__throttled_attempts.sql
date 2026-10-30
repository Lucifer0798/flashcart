-- ADR 0060: the table that counts failed sign-ins now also counts password-reset requests, so it is named
-- for what it holds: attempts at something limited, each as a hash of what is being limited.
alter table sign_in_failures rename to throttled_attempts;
alter table throttled_attempts rename column failed_at to attempted_at;
alter index ix_sign_in_failures_key rename to ix_throttled_attempts_key;
alter index ix_sign_in_failures_age rename to ix_throttled_attempts_age;
