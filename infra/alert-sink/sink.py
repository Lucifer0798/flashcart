"""The local stand-in for a pager. See ADR 0043.

Alertmanager posts here. Each notification is logged as one line, kept in memory, and listed at
GET /alerts, newest first, so a firing alert is something you can see with curl rather than something
that happened in a UI nobody had open.

It also keeps the dead man's switch. Prometheus fires Watchdog permanently and Alertmanager forwards it
here every minute. GET /health answers 503 once it has not arrived for WATCHDOG_MAX_AGE seconds, so
`docker compose ps` shows this container unhealthy when Prometheus, the rules or Alertmanager have
stopped -- the one failure no alert rule can report, because it is the thing that reports them.

Standard library only: this is the one container in the stack built from a script, and a dependency
here would be a supply chain added to receive a JSON post.
"""
import collections
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_AGE = int(os.environ.get('WATCHDOG_MAX_AGE', '180'))
KEEP = int(os.environ.get('KEEP', '500'))

started = time.time()
lock = threading.Lock()
received = collections.deque(maxlen=KEEP)
last_heartbeat = None
heartbeats = 0


def summarise(route, payload):
    """One entry per alert in the notification, which is what a reader wants to scan."""
    entries = []
    for alert in payload.get('alerts', []):
        labels = alert.get('labels', {})
        entries.append({
            'receivedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
            'route': route,
            'status': alert.get('status'),
            'alertname': labels.get('alertname'),
            'severity': labels.get('severity'),
            'service': labels.get('service'),
            'summary': alert.get('annotations', {}).get('summary'),
            'startsAt': alert.get('startsAt'),
            'labels': labels,
        })
    return entries


class Handler(BaseHTTPRequestHandler):

    def _json(self, status, body):
        data = json.dumps(body, indent=2).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        length = int(self.headers.get('Content-Length') or 0)
        try:
            return json.loads(self.rfile.read(length) or b'{}')
        except ValueError:
            return None

    def do_POST(self):
        global last_heartbeat, heartbeats
        payload = self._body()
        if payload is None:
            self._json(400, {'error': 'body is not JSON'})
            return

        if self.path == '/heartbeat':
            with lock:
                last_heartbeat = time.time()
                heartbeats += 1
            self._json(200, {'ok': True})
            return

        if self.path.startswith('/alerts/'):
            route = self.path[len('/alerts/'):] or 'unknown'
            entries = summarise(route, payload)
            with lock:
                for entry in entries:
                    received.appendleft(entry)
            for entry in entries:
                # One line per alert, so `docker compose logs alert-sink` reads as a pager history.
                print(f"[{entry['route']}] {entry['status']:8} {entry['alertname']} "
                      f"service={entry['service']} -- {entry['summary']}", flush=True)
            self._json(200, {'ok': True, 'received': len(entries)})
            return

        self._json(404, {'error': 'no such path'})

    def do_GET(self):
        if self.path.startswith('/alerts'):
            with lock:
                self._json(200, list(received))
            return

        if self.path == '/health':
            now = time.time()
            with lock:
                seen, count = last_heartbeat, heartbeats
            if seen is None:
                age = None
                # Prometheus needs a moment to evaluate its first rules after a cold start. Within the
                # same window a heartbeat would be allowed to be late, a missing first one is not yet
                # a fault.
                healthy = now - started < MAX_AGE
            else:
                age = round(now - seen)
                healthy = age < MAX_AGE
            self._json(200 if healthy else 503, {
                'status': 'ok' if healthy else 'WATCHDOG_SILENT',
                'heartbeats': count,
                'watchdogLastSeenSecondsAgo': age,
                'maxAgeSeconds': MAX_AGE,
            })
            return

        self._json(404, {'error': 'no such path'})

    def log_message(self, fmt, *args):
        # The default logs every request, health checks included, which buries the alert lines.
        pass


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print(f'alert-sink listening on {port}; watchdog max age {MAX_AGE}s', flush=True)
    ThreadingHTTPServer(('', port), Handler).serve_forever()
