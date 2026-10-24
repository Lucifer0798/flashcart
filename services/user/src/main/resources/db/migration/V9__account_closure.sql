-- ADR 0053: a customer can close their account.
--
-- Closing keeps the row, because the id is what every other service's records point at, and a row with
-- the same id is what tells a later reader the account existed and was closed rather than never was. What
-- it removes is everything that identified a person: the email, the name and the password are overwritten
-- in place, the addresses and emails sent are deleted.

alter table users drop constraint ck_users_status;
alter table users add constraint ck_users_status check (status in ('ACTIVE', 'DISABLED', 'CLOSED'));

alter table users add column closed_at timestamptz;

-- A closed account carries no identity, and an open one is not marked closed. Checked here as well as in
-- the code, because the likeliest wrong way to close one is an UPDATE typed by hand that forgets a column.
alter table users add constraint ck_users_closed check (
    (status = 'CLOSED') = (closed_at is not null)
    and (status <> 'CLOSED' or (email = 'closed-' || id || '@closed.invalid' and roles = '')));
