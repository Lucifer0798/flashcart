#!/usr/bin/env python3
"""Fails if a decision record is missing from either list of them, or a link to one is broken.

    python3 scripts/adr-index-check.py     # `python` on Windows

There are two lists: docs/adr/README.md, the chronological index, and docs/README.md, the map that
groups records by what they are about. Both are written by hand, and the map silently stopped at 0039
while nine more records were written -- and the page that introduced it went on saying "twenty decision
records" past forty. A list a reader trusts and nobody checks is worse than no list.

So every docs/adr/0NNN-*.md must be linked from both, and every link from either must resolve.
"""
import glob
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADR_DIR = os.path.join(ROOT, 'docs', 'adr')
LISTS = {
    'docs/adr/README.md': os.path.join(ADR_DIR, 'README.md'),
    'docs/README.md': os.path.join(ROOT, 'docs', 'README.md'),
}

LINK = re.compile(r'\((?:adr/)?(0\d{3}-[A-Za-z0-9-]+\.md)\)')


def main():
    records = sorted(os.path.basename(p) for p in glob.glob(os.path.join(ADR_DIR, '0[0-9][0-9][0-9]-*.md')))
    if not records:
        print('no decision records found under docs/adr -- the check would pass vacuously')
        return 1

    failures = 0
    for name, path in LISTS.items():
        with io.open(path, encoding='utf-8') as handle:
            linked = set(LINK.findall(handle.read()))
        missing = [r for r in records if r not in linked]
        broken = sorted(l for l in linked if l not in records)
        for record in missing:
            print('MISSING  %-20s does not link %s' % (name, record))
        for link in broken:
            print('BROKEN   %-20s links %s, which does not exist' % (name, link))
        failures += len(missing) + len(broken)
        print('%-20s %d of %d records linked' % (name, len(records) - len(missing), len(records)))

    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
