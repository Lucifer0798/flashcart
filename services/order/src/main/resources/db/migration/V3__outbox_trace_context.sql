-- Phase 11: carry the trace across the outbox.
--
-- The outbox deliberately separates "decide to publish" from "publish": the row commits with the
-- business change, and a relay sends it some time later on a different thread, in a different
-- request, possibly after a restart. That is the point of the pattern, and it is also exactly what
-- breaks a distributed trace in half.
--
-- Without this column a checkout traces as two unrelated fragments -- the HTTP request that queued
-- the message, and a rootless relay span that sent it -- which is worst precisely when it matters
-- most, because a saga that stalls mid-flight is the thing you most want to follow end to end.
--
-- So the W3C traceparent is captured at queue time and replayed as a header at send time. The
-- consumer then continues the trace the buyer's request started, across a hop that may have taken
-- minutes.
alter table outbox_messages add column trace_parent varchar(64);

comment on column outbox_messages.trace_parent is
    'W3C traceparent captured when the message was queued, replayed as a header when the relay sends it.';
