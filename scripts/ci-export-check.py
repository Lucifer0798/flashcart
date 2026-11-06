#!/usr/bin/env python3
"""Checks the files a real shopper downloads after a real checkout and refund. See ADR 0066.

    python3 scripts/ci-export-check.py ORDER_NUMBER USER.json ORDER.json PAYMENT.json SHIPPING.json

The integration tests prove each service alone, with rows they seeded. This proves the files agree about an
order that went through the whole platform, and that nothing a customer should not hold came along.

Field names are checked rather than the raw text, because an email or a cancellation reason may honestly say
"attempts". A working reset link is the exception: it is looked for anywhere, because it would be in a body.
"""
import json
import sys

FORBIDDEN_FIELDS = ("hash", "lasterror", "attempts", "nextattemptat", "operatorid", "grantedby")


def fields(value):
    if isinstance(value, dict):
        for key, inner in value.items():
            yield key
            yield from fields(inner)
    elif isinstance(value, list):
        for inner in value:
            yield from fields(inner)


def main():
    order_number, user_path, order_path, payment_path, shipping_path = sys.argv[1:]
    raws = {path: open(path, encoding="utf-8").read() for path in (user_path, order_path, payment_path, shipping_path)}

    for path, raw in raws.items():
        if "token=" in raw:
            sys.exit(f"{path} contains a reset link")
        for field in fields(json.loads(raw)):
            if any(word in field.lower() for word in FORBIDDEN_FIELDS):
                sys.exit(f"{path} has a field called {field!r}")

    user = json.loads(raws[user_path])
    emails = [email for email in user["emails"] if email["orderNumber"] == order_number]
    if not any(email["status"] == "SENT" for email in emails):
        sys.exit(f"no sent email about {order_number} in the user export: {emails}")
    if not user["profile"]["email"]:
        sys.exit("no email address in the profile")
    if not user["sessions"]:
        sys.exit("no sessions in the user export, for an account that is signed in")
    if not any(entry["action"] == "READ_NOTICES" for entry in user["staffAccess"]):
        sys.exit("support read this shopper's emails, and the user export does not say so")

    orders = json.loads(raws[order_path])["orders"]
    found = [entry for entry in orders if entry["order"]["orderNumber"] == order_number]
    if not found or not found[0]["history"]:
        sys.exit(f"{order_number} is not in the order export, or has no history")

    payments = json.loads(raws[payment_path])["payments"]
    if not any(payment["orderNumber"] == order_number for payment in payments):
        sys.exit(f"{order_number} is not in the payment export")

    customer = user["customerId"]
    for path in (order_path, payment_path, shipping_path):
        if json.loads(raws[path])["customerId"] != customer:
            sys.exit(f"{path} is about somebody other than {customer}")

    print(f"exports agree about {order_number}: {len(user['emails'])} emails, {len(orders)} orders, {len(payments)} payments")


if __name__ == "__main__":
    main()
