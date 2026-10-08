#!/usr/bin/env bash
# Grant or revoke a staff role, on the record. See ADR 0047.
#
#   scripts/grant-role.sh grant  <email> <WAREHOUSE|CATALOG|SUPPORT> --reason "why"
#   scripts/grant-role.sh grant  <email> OPERATOR --break-glass --reason "why, and until when"
#   scripts/grant-role.sh revoke <email> <ROLE> --reason "why"
#
# Exit codes: 0 done (or nothing to change), 2 bad arguments, 3 OPERATOR refused, 4 no such account.
#
# Roles are granted by changing users.roles directly -- there is no endpoint, deliberately (ADR 0022):
# anyone who can grant a role through an API can be tricked into granting one. This script is that
# change made safely:
#
#   - only roles that exist. A typo used to be stored, issued in every token, and matched nothing
#   - OPERATOR, which can do everything, only with --break-glass. The narrow roles are the default
#   - the change and a row in role_grants -- who, what, why, when -- in one transaction
#
# Runs against the compose stack's database by default. Set DATABASE_URL to point it at another.
set -euo pipefail

usage() {
  sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

[ $# -ge 3 ] || usage
ACTION="$1"; EMAIL="$2"; ROLE="$3"; shift 3
REASON=""
BREAK_GLASS=false
while [ $# -gt 0 ]; do
  case "$1" in
    --reason) REASON="${2:-}"; shift 2 ;;
    --break-glass) BREAK_GLASS=true; shift ;;
    *) echo "unknown option: $1" >&2; usage ;;
  esac
done

case "$ACTION" in grant|revoke) ;; *) echo "action must be grant or revoke, not '$ACTION'" >&2; exit 2 ;; esac
case "$ROLE" in
  WAREHOUSE|CATALOG|SUPPORT|OPERATOR) ;;
  *) echo "unknown role '$ROLE': must be one of WAREHOUSE, CATALOG, SUPPORT, OPERATOR" >&2; exit 2 ;;
esac
if [ -z "${REASON// /}" ]; then
  echo "--reason is required: a role change with no reason is one nobody can review later" >&2
  exit 2
fi
if [ "$ACTION" = grant ] && [ "$ROLE" = OPERATOR ] && [ "$BREAK_GLASS" != true ]; then
  cat >&2 <<'EOF'
refusing to grant OPERATOR without --break-glass.

OPERATOR can do everything every other role can, across every service. The work is split into
WAREHOUSE (stock, reservations, dispatch), CATALOG (products, sales) and SUPPORT (reading customers'
data and who read it) -- grant the one the job needs. If the job genuinely needs everything, say so
with --break-glass and a reason that says until when.
EOF
  exit 3
fi

OS_USER="${USER:-${USERNAME:-unknown}}"
SQL_ACTION=$(printf '%s' "$ACTION" | tr '[:lower:]' '[:upper:]')

psql_run() {
  if [ -n "${DATABASE_URL:-}" ]; then
    psql "$DATABASE_URL" -X -q -t -A "$@"
  else
    docker compose exec -T postgres psql -U flashcart -d flashcart_user -X -q -t -A "$@"
  fi
}

# Checked first and on its own: otherwise an unknown address surfaces as psql's "no rows returned for
# \gset", with psql's exit code 3 -- the same code this script uses for refusing OPERATOR.
FOUND=$(psql_run -v email="$EMAIL" <<'SQL'
select count(*) from users where lower(email) = lower(:'email');
SQL
)
if [ "$(printf '%s' "$FOUND" | tr -d '[:space:]')" != "1" ]; then
  echo "no account with email '$EMAIL'" >&2
  exit 4
fi

# Inputs travel as psql variables and are quoted by psql (:'name'), never spliced into the SQL text.
psql_run -v ON_ERROR_STOP=1 -v email="$EMAIL" -v role="$ROLE" -v action="$SQL_ACTION" \
  -v reason="$REASON" -v osuser="$OS_USER" <<'SQL'
begin;

select id as user_id, roles as before
  from users where lower(email) = lower(:'email')
   for update
\gset

select case
         when :'action' = 'GRANT'
           then coalesce((select string_agg(r, ',' order by r)
                            from (select unnest(string_to_array(nullif(:'before', ''), ','))
                                  union select :'role') held(r)), '')
         else coalesce((select string_agg(r, ',' order by r)
                          from unnest(string_to_array(nullif(:'before', ''), ',')) held(r)
                         where r <> :'role'), '')
       end as after
\gset

update users set roles = :'after', updated_at = now() where id = :'user_id';

-- Recorded only when something changed: granting a role already held, or revoking one not held, is a
-- no-op, and an audit trail of no-ops buries the changes.
insert into role_grants (user_id, role, action, reason, granted_by)
select :'user_id', :'role', :'action', :'reason', current_user || ' / ' || :'osuser'
 where :'before' is distinct from :'after';

select format('%s: %s -> %s', :'email', nullif(:'before', ''), nullif(:'after', ''));

commit;
SQL
