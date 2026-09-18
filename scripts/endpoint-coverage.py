#!/usr/bin/env python3
"""Fails if a controller mapping is exercised by nothing.

    python3 scripts/endpoint-coverage.py     # `python` on Windows, where python3 is not a thing

Enumerates every @RequestMapping/@GetMapping/... in the platform and looks for a request to that
path shape in the integration tests, the CI workflow and both harnesses. An endpoint nothing calls
is not necessarily broken, but nothing would say so if it were.

Why this is a script rather than a claim
---------------------------------------

It has been got wrong twice by hand, in opposite directions, and both times the wrong answer looked
right:

* Matching the literal prefix of the path reported everything with a ``{placeholder}`` mid-path as
  covered, because ``/api/v1/flash-sales/{id}/items/{itemId}`` reduces to ``/api/v1/flash-sales``,
  which appears in any flash-sale test. That produced a confident "all 60 covered" while
  ``DELETE .../items/{itemId}`` had no test at all.
* Requiring each placeholder to be a single literal segment reported nine endpoints as uncovered,
  because a test writes ``"/api/v1/flash-sales/" + sale.id() + "/schedule"`` and the concatenation
  is not a path segment.

So a placeholder here matches anything that is not a slash or a newline, and the detector checks
itself against known answers before reporting. A coverage checker that cannot fail is worth less
than no coverage checker, because it is believed.
"""
import glob
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Paths a request can be made from. The harnesses count: an endpoint only the chaos script calls is
# still exercised, and pretending otherwise would push people to write a duplicate test.
SOURCES = (
    glob.glob(os.path.join(ROOT, 'services/*/src/test/**/*.java'), recursive=True)
    + [os.path.join(ROOT, '.github/workflows/ci.yml'),
       os.path.join(ROOT, 'chaos/inject.sh'),
       os.path.join(ROOT, 'load/run.sh'),
       os.path.join(ROOT, 'load/scripts/flash-sale.js')]
)

# Endpoints reached in a way no textual search can see, with the reason. Keep this list short and
# argued: every entry is a place the check has been switched off, and a long one means the check is
# being worked around rather than satisfied.
ALLOWED = {
    # ci.yml builds this in a loop: for s in inventory order payment shipping; ... "$G/api/v1/$s/_info"
    '/api/v1/shipping/_info': 'built from a shell loop variable in ci.yml',
}


def read(path):
    with io.open(path, encoding='utf-8', errors='replace') as handle:
        return handle.read()


def endpoints():
    found = []
    pattern = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\("([^"]*)"\))?')
    for path in glob.glob(os.path.join(ROOT, 'services/*/src/main/java/**/api/*Controller.java'),
                          recursive=True):
        source = read(path)
        base = re.search(r'@RequestMapping\("([^"]+)"\)', source)
        base = base.group(1) if base else ''
        for match in pattern.finditer(source):
            verb = match.group(1).upper()
            full = (base + (match.group(2) or '')).rstrip('/')
            found.append((verb, full, os.path.basename(path)))
    return sorted(found, key=lambda row: (row[1], row[0]))


def matcher(haystack):
    def covered(path):
        parts = ['[^/\n]+?' if p.startswith('{') else re.escape(p)
                 for p in path.strip('/').split('/')]
        return re.search('/' + '/'.join(parts), haystack) is not None
    return covered


def main():
    haystack = ''.join(read(p) for p in SOURCES)
    covered = matcher(haystack)

    # The detector is checked before it is trusted. These are answers known by having read the
    # suites; if it gets one wrong its verdict on everything else is worthless.
    wrong = [p for p in ['/api/v1/flash-sales/{id}/schedule',
                         '/api/v1/orders/{orderNumber}/cancel',
                         '/api/v1/inventory/stock/{sku}/receive',
                         '/api/v1/users/me'] if not covered(p)]
    wrong += [p for p in ['/api/v1/products/definitely-not-an-endpoint'] if covered(p)]
    if wrong:
        print('detector self-check FAILED on: %s' % ', '.join(wrong))
        print('Refusing to report: a coverage check that is itself wrong is worse than none.')
        return 2

    rows = endpoints()
    missing = [r for r in rows if not covered(r[1]) and r[1] not in ALLOWED]
    allowed = [r for r in rows if not covered(r[1]) and r[1] in ALLOWED]

    for verb, path, cls in allowed:
        print('allowed  %-6s %-50s %s' % (verb, path, ALLOWED[path]))
    for verb, path, cls in missing:
        print('MISSING  %-6s %-50s %s' % (verb, path, cls))

    print('%d endpoints, %d exercised by nothing, %d allowed'
          % (len(rows), len(missing), len(allowed)))
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main())
