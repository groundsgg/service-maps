#!/usr/bin/env python3
"""Isolated OIDC/PackSet/Kubernetes fixtures and HTTPS proxy to the real S3 server."""
import hashlib
import http.client
import http.server
import json
import pathlib
import ssl
import threading
import time
import urllib.parse

ROOT = pathlib.Path('/fixtures')
HOST = 'https://maps-fixtures'
JOBS = {}
LOCK = threading.Lock()


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()


def documents(channel):
    build = '1969c1e6a3799e976de46eab019a16b2ee257ea7'
    version = '1.2.3' if channel == 'stable' else '0.0.0-edge.1.g' + build[:12]
    publication = {'type': 'release', 'id': 'v1.2.3'} if channel == 'stable' else {'type': 'build', 'id': build}
    directory = 'releases' if channel == 'stable' else 'builds'
    base = f'{HOST}/resourcepacks/packsets/global/{directory}/{publication["id"]}'
    suffix = 'v1.2.3' if channel == 'stable' else 'edge-' + build[:12]
    manifest = encoded({
        'schemaVersion': 2, 'packSet': 'global', 'version': version, 'publication': publication,
        'minecraft': {'version': '26.3', 'resourcePackFormat': 97},
        'catalog': {'id': 'grounds:resourcepacks', 'version': version,
                    'coordinate': 'gg.grounds:resourcepacks-catalog:' + version,
                    'file': 'grounds-resourcepack-catalog-' + suffix + '.jar', 'sha256': 'a' * 64, 'size': 3},
        'packs': [
            {'id': 'grounds-content', 'order': 0, 'required': True, 'resourcePackFormat': 97,
             'role': 'content', 'sha1': 'b' * 40, 'sha256': 'c' * 64, 'size': 4,
             'url': base + '/grounds-content-pack-' + suffix + '.zip',
             'uuid': '44591d5b-71f5-5c2a-a5b2-d3ee7be47e53'},
            {'id': 'grounds-platform', 'order': 1, 'required': True, 'resourcePackFormat': 97,
             'role': 'platform', 'sha1': 'd' * 40, 'sha256': 'e' * 64, 'size': 5,
             'url': base + '/grounds-platform-pack-' + suffix + '.zip',
             'uuid': '8da7cffe-bb04-55e0-9868-7789ce5de362'}],
        'provenance': {'commit': build, 'repository': 'groundsgg/resourcepacks'}})
    pointer = encoded({'schemaVersion': 2, 'packSet': 'global', 'channel': channel, 'sequence': 1,
                       'target': publication, 'manifest': {'url': base + '/manifest.json',
                       'sha256': hashlib.sha256(manifest).hexdigest(), 'size': len(manifest)}})
    return pointer, manifest


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Never log bearer tokens, presigned URLs or bodies.

    def reply(self, status, value=None, raw=None):
        body = raw if raw is not None else encoded(value)
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)

    def handle_request(self):
        path = urllib.parse.urlsplit(self.path).path
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        if path.startswith(('/private', '/public', '/ugc')):
            headers = {k: v for k, v in self.headers.items() if k.lower() not in ('connection', 'expect')}
            connection = http.client.HTTPConnection('storage', 9000, timeout=30)
            connection.request(self.command, self.path, body, headers)
            response = connection.getresponse()
            data = response.read()
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in ('transfer-encoding', 'connection', 'content-length'):
                    self.send_header(key, value)
            self.send_header('Content-Length', response.getheader('Content-Length', '0') if self.command == 'HEAD' else str(len(data)))
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(data)
            connection.close()
        elif path == '/realm/.well-known/openid-configuration':
            issuer = 'http://maps-fixtures:8080/realm'
            self.reply(200, {'issuer': issuer, 'jwks_uri': issuer + '/certs',
                            'token_endpoint': issuer + '/token', 'authorization_endpoint': issuer + '/authorize'})
        elif path == '/realm/certs':
            self.reply(200, json.loads((ROOT / 'jwks.json').read_text()))
        elif path.startswith('/resourcepacks/'):
            channel = 'edge' if '/edge.json' in path or '/builds/' in path else 'stable'
            pointer, manifest = documents(channel)
            self.reply(200, raw=pointer if '/channels/' in path else manifest)
        elif path == '/contract/jobs':
            with LOCK:
                self.reply(200, list(JOBS.values()))
        elif path.startswith('/contract/complete/'):
            with LOCK:
                job = JOBS[path.rsplit('/', 1)[1]]
                job['status'] = {'succeeded': 1, 'conditions': [{'type': 'Complete', 'status': 'True'}]}
                self.reply(200, job)
        elif '/namespaces/maps/jobs' in path:
            name = path.split('/jobs/', 1)[1] if '/jobs/' in path else None
            if self.command == 'POST':
                job = json.loads(body)
                with LOCK:
                    JOBS[job['metadata']['name']] = job
                self.reply(201, job)
            elif 'watch=true' in self.path:
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                # Polling is the durable completion path; retain a quiet watch between polls.
                time.sleep(45)
            elif name:
                with LOCK:
                    job = JOBS.get(name)
                self.reply(200 if job else 404, job or {'kind': 'Status', 'apiVersion': 'v1', 'code': 404})
            else:
                with LOCK:
                    jobs = list(JOBS.values())
                self.reply(200, {'kind': 'JobList', 'apiVersion': 'batch/v1', 'metadata': {'resourceVersion': '1'}, 'items': jobs})
        else:
            self.reply(404, {'path': path})

    do_GET = do_POST = do_PUT = do_HEAD = do_DELETE = handle_request


plain = http.server.ThreadingHTTPServer(('0.0.0.0', 8080), Handler)
threading.Thread(target=plain.serve_forever, daemon=True).start()
secure = http.server.ThreadingHTTPServer(('0.0.0.0', 443), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(ROOT / 'cert.pem', ROOT / 'key.pem')
secure.socket = context.wrap_socket(secure.socket, server_side=True)
secure.serve_forever()
