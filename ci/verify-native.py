#!/usr/bin/env python3
"""Run populated production-image contracts with real Postgres, S3 and NATS.

OIDC discovery/JWKS, validated HTTPS PackSets and the Kubernetes API are isolated
fixtures. Both unmodified JVM Job entrypoints execute against the same S3 objects.
"""
import base64
import datetime
import hashlib
import hmac
import http.client
import io
import json
import os
import pathlib
import shutil
import socket
import ssl
import struct
import subprocess
import tarfile
import tempfile
import time
import urllib.parse
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
PREFIX = os.environ.get('NATIVE_IMAGE_PREFIX', 'grounds/service-maps')
IMAGE = os.environ.get('MAPS_CONTRACT_IMAGE', PREFIX + ':native-micro-pilot')
WORKER = os.environ.get('MAPS_WORKER_IMAGE', PREFIX + ':jvm-worker-contract')
WORKER_DIGEST = 'ghcr.io/groundsgg/service-maps@sha256:' + 'a' * 64


def command(*args, check=True, **kwargs):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs)
    if check and result.returncode:
        # Build/test output contains no real credentials or presigned request bodies.
        raise AssertionError(f'{args[0]} failed ({result.returncode}): {result.stdout.decode()[-12000:]}')
    return result.stdout.decode().strip()


def docker(*args, **kwargs):
    return command('docker', *args, **kwargs)


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def b64(data):
    return base64.urlsafe_b64encode(data).decode().rstrip('=')


def eventually(action, seconds=75):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            return action()
        except (AssertionError, OSError, http.client.HTTPException) as error:
            last = error
            time.sleep(.5)
    raise AssertionError(f'contract timed out: {last}')


def worker_image():
    if 'MAPS_WORKER_IMAGE' in os.environ:
        return
    properties = {}
    path = pathlib.Path(os.environ.get('GRADLE_USER_HOME', pathlib.Path.home() / '.gradle')) / 'gradle.properties'
    if path.exists():
        properties = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line and not line.startswith('#'))
    token = os.environ.get('PACKAGES_TOKEN', properties.get('github.token'))
    actor = os.environ.get('GITHUB_ACTOR', properties.get('github.user'))
    assert token and actor, 'protected package credentials are required to build JVM workers'
    with tempfile.NamedTemporaryFile() as secret:
        secret.write(token.encode())
        secret.flush()
        docker('build', '--build-arg', 'GITHUB_USER=' + actor, '--secret', 'id=github_token,src=' + secret.name,
               '-t', WORKER, '.', cwd=ROOT)


class Contract:
    def __init__(self, fixtures):
        self.fixtures = fixtures
        self.name = 'maps-contract-' + uuid.uuid4().hex[:10]
        self.containers = []
        self.ports = {}
        self.context = ssl.create_default_context(cafile=str(fixtures / 'cert.pem'))
        self.context.check_hostname = False

    def run(self, name, image, *args, ports=(), env=None, command_args=()):
        full = self.name + '-' + name
        options = ['run', '-d', '--name', full, '--network', self.name, '--network-alias', name]
        for port in ports:
            options += ['-p', '127.0.0.1::' + str(port)]
        for key, value in (env or {}).items():
            options += ['-e', key]
        self.containers.append(full)
        docker(*options, *args, image, *command_args, env={**os.environ, **(env or {})})
        for port in ports:
            self.ports[name, port] = int(docker('port', full, str(port) + '/tcp').rsplit(':', 1)[1])
        return full

    def request(self, path, method='GET', value=None, token=None, status=200, service='api', raw=None, headers=None):
        port = self.ports[service, 443 if service == 'maps-fixtures' else 9000 if service == 'api' else 8080]
        connection = (http.client.HTTPSConnection('127.0.0.1', port, context=self.context, timeout=10)
                      if service == 'maps-fixtures' else http.client.HTTPConnection('127.0.0.1', port, timeout=10))
        request_headers = dict(headers or {})
        if service == 'maps-fixtures':
            request_headers.pop('host', None)
            request_headers['Host'] = 'maps-fixtures'
        if token:
            request_headers['Authorization'] = 'Bearer ' + token
        body = encoded(value) if value is not None else raw
        if value is not None:
            request_headers['Content-Type'] = 'application/json'
        connection.request(method, path, body, request_headers)
        response = connection.getresponse()
        data = response.read()
        connection.close()
        assert response.status == status, f'{method} {urllib.parse.urlsplit(path).path} expected {status}, got {response.status}: {data[:600]!r}'
        return json.loads(data) if data and response.getheader('Content-Type', '').startswith('application/json') else data

    def s3(self, path, method='GET', body=b''):
        now = datetime.datetime.now(datetime.timezone.utc)
        stamp, day = now.strftime('%Y%m%dT%H%M%SZ'), now.strftime('%Y%m%d')
        payload = digest(body)
        headers = {'host': 'maps-fixtures', 'x-amz-content-sha256': payload, 'x-amz-date': stamp}
        names = ';'.join(headers)
        canonical = '\n'.join((method, path, '', ''.join(f'{k}:{v}\n' for k, v in headers.items()), names, payload))
        scope = f'{day}/us-east-1/s3/aws4_request'
        signed = '\n'.join(('AWS4-HMAC-SHA256', stamp, scope, digest(canonical.encode())))
        key = b'AWS4contract-secret'
        for part in (day, 'us-east-1', 's3', 'aws4_request'):
            key = hmac.new(key, part.encode(), hashlib.sha256).digest()
        signature = hmac.new(key, signed.encode(), hashlib.sha256).hexdigest()
        headers['Authorization'] = f'AWS4-HMAC-SHA256 Credential=contract/{scope}, SignedHeaders={names}, Signature={signature}'
        return self.request(path, method, raw=body, headers=headers, service='maps-fixtures')

    def presigned(self, url, method='GET', body=None):
        uri = urllib.parse.urlsplit(url)
        assert uri.scheme == 'https' and uri.netloc == 'maps-fixtures'
        query = urllib.parse.parse_qs(uri.query)
        assert query['X-Amz-Algorithm'] == ['AWS4-HMAC-SHA256']
        return self.request(uri.path + '?' + uri.query, method, raw=body, service='maps-fixtures')

    def token(self, subject='staff', groups=('grounds-staff',), audience='service-maps', expires=300):
        claims = {'iss': 'http://maps-fixtures:8080/realm', 'sub': subject, 'aud': audience,
                  'groups': list(groups), 'iat': int(time.time()), 'exp': int(time.time()) + expires}
        payload = b64(encoded({'alg': 'RS256', 'kid': 'contract'})) + '.' + b64(encoded(claims))
        signature = subprocess.check_output(['openssl', 'dgst', '-sha256', '-sign', str(self.fixtures / 'key.pem')], input=payload.encode())
        return payload + '.' + b64(signature)

    def jobs(self, kind):
        return [job for job in self.request('/contract/jobs', service='control')
                if job['metadata']['labels']['app.kubernetes.io/name'] == kind]

    def job(self, kind):
        jobs = self.jobs(kind)
        assert jobs, f'no {kind} Job was created'
        return jobs[-1]

    def execute(self, job):
        pod = job['spec']['template']['spec']
        container = pod['containers'][0]
        assert container['image'] == WORKER_DIGEST, 'Job must keep its separate immutable JVM worker image'
        assert pod['serviceAccountName'] == 'service-maps-derive-worker'
        assert pod['automountServiceAccountToken'] is False
        assert container['securityContext']['readOnlyRootFilesystem'] is True
        assert container['command'][0] == 'java'
        env = {entry['name']: entry['value'] for entry in container['env']}
        name = self.name + '-worker-' + str(len(self.containers))
        env['JAVA_TOOL_OPTIONS'] = '-Djavax.net.ssl.trustStore=/fixtures/trust.p12 -Djavax.net.ssl.trustStorePassword=changeit'
        options = ['run', '--name', name, '--network', self.name, '--read-only', '--tmpfs', '/work:exec,uid=65532,gid=65532',
                   '-v', str(self.fixtures) + ':/fixtures:ro']
        for key, value in env.items():
            options += ['-e', key]
        self.containers.append(name)
        docker(*options, '--entrypoint', 'java', WORKER, *container['command'][1:], env={**os.environ, **env})
        self.request('/contract/complete/' + job['metadata']['name'], 'POST', {}, service='control')

    def close(self):
        for name in reversed(self.containers):
            docker('rm', '-f', name, check=False)
        docker('network', 'rm', self.name, check=False)


def source_bundle():
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode='w') as tar:
        # An empty Anvil region is a valid world and exercises the real Zstd/archive worker.
        for name, body in [('region/r.0.0.mca', bytes(8192)), ('native-contract.txt', b'packaged maps contract\n')]:
            entry = tarfile.TarInfo(name)
            entry.size = len(body)
            tar.addfile(entry, io.BytesIO(body))
        end = tar.offset
    # The strict worker accepts exactly two terminal records, without tarfile's extra record padding.
    data = archive.getvalue()[:end + 1024]
    # One raw-block Zstandard frame: no optional Python compression dependency in CI.
    return b'\x28\xb5\x2f\xfd\xa0' + struct.pack('<I', len(data)) + ((len(data) << 3) | 1).to_bytes(3, 'little') + data


def assert_prometheus_metrics(c, populated=False):
    metrics = c.request('/q/metrics', headers={'Accept': 'text/plain'})
    assert isinstance(metrics, bytes) and b'# TYPE ' in metrics, 'Prometheus text format is required'
    if populated:
        assert b'http_server_requests_seconds_count' in metrics, 'HTTP request metrics are required after API traffic'
        for name in (b'derive_reconciliation_active_candidates', b'derive_reconciliation_attempts_total',
                     b'derive_reconciliation_duration_seconds_count'):
            assert name in metrics, 'Populated derive metric missing: ' + name.decode()


def contracts(c):
    assert_prometheus_metrics(c)
    staff = c.token()
    creator = c.token('creator', ())
    other = c.token('other', ())
    c.request('/v1/maps', status=401)
    c.request('/v1/maps', token=c.token(audience='another-service'), status=401)
    c.request('/v1/maps', token=c.token(expires=-300), status=401)
    c.request('/v1/maps', 'POST', {'address': 'grounds/refused', 'kind': 'ARENA'}, token=creator, status=403)
    address = 'grounds/native-contract'
    path = '/v1/maps/' + address
    created = c.request('/v1/maps', 'POST', {'address': address, 'displayName': 'Native API contract',
                        'kind': 'ARENA', 'stateful': True}, staff, 201)
    assert created['ownerSub'] == 'staff' and created['trust'] == 'FIRST_PARTY' and created['stateful'] is True
    assert created['createdAt'] and created['displayName'] == 'Native API contract'
    assert c.request(path, token=staff) == created
    assert c.request(path, token=staff, headers={'Accept-Encoding': 'zstd'}) == created
    assert created in c.request('/v1/maps?namespace=grounds', token=staff)
    c.request('/v1/maps', 'POST', {'address': address, 'kind': 'ARENA'}, staff, 409)
    c.request('/v1/maps', 'POST', {'address': 'u/creator/private-map', 'kind': 'PLOT'}, creator, 201)
    c.request('/v1/maps/u/creator/private-map', token=other, status=404)
    assert not c.request('/v1/maps?namespace=u/creator', token=other)
    print('Maps: JWT signature/audience/expiry, staff groups, creator ownership, visibility, populated CRUD passed')

    # Preserve the compatibility publish path as well as required asynchronous derivation.
    upload = c.request(path + '/uploads', 'POST', token=staff)
    bundle = source_bundle()
    c.presigned(upload['url'], 'PUT', bundle)
    version = c.request(path + '/versions', 'POST', {'uploadId': upload['uploadId'],
                        'sourceSha256': digest(bundle), 'note': 'legacy assembled bundle'}, staff, 201)
    assert version['version'] == 1 and version['state'] == 'DRAFT' and version['scene']['status'] == 'PENDING'
    c.request(path + '/pins/stage', 'POST', {'version': 1}, staff, 409)
    published = c.request(path + '/versions/1/publish', 'POST', {'bundleSha256': digest(bundle),
                          'sizeBytes': len(bundle), 'presentChunks': 0, 'estLoadedMib': 1}, staff)
    assert published['state'] == 'PUBLISHED' and published['sizeBytes'] == len(bundle)
    assert c.s3('/public/bundle/sha256/' + digest(bundle)[:2] + '/' + digest(bundle) + '.tar.zst') == bundle
    c.request(path + '/pins/stage', 'POST', {'version': 1}, creator, 403)

    subscribers = []
    try:
        for _ in range(2):
            connection = socket.create_connection(('127.0.0.1', c.ports['broker', 4222]), timeout=15)
            connection.sendall(b'CONNECT {}\r\nSUB maps.pins.stage 1\r\nPING\r\n')
            stream = connection.makefile('rb')
            while stream.readline() != b'PONG\r\n':
                pass
            subscribers.append((connection, stream))
        pin = c.request(path + '/pins/stage', 'POST', {'version': 1}, staff)
        assert pin['pinFilePublished'] is True and pin['movedBySub'] == 'staff'
        pinfile = c.s3('/public/pins/stage.json')
        entry = pinfile['maps'][address]
        assert entry['version'] == 1 and entry['bundleSha256'] == digest(bundle) and 'lit' not in entry
        assert entry['bundleUrl'].startswith('https://maps.grounds.gg/bundle/sha256/')
        assert c.request(path + '/pins', token=staff)[0]['environment'] == 'stage'
        for _, stream in subscribers:
            line = stream.readline()
            assert line.startswith(b'MSG maps.pins.stage '), line
            event = json.loads(stream.read(int(line.split()[-1])))
            assert stream.read(2) == b'\r\n' and event == pinfile
    finally:
        for connection, stream in subscribers:
            stream.close()
            connection.close()
    fork = c.request(path + '/forks', 'POST', {'target': 'grounds/native-fork', 'fromVersion': 1}, staff, 201)
    assert fork['forkedFrom'].endswith('@1')
    uuid.UUID(fork['forkedFrom'].split('@')[0])
    print('Maps: presigned upload, S3 promotion, immutable versions, pin-file reflection, fork and two NATS consumers passed')

    workspace = '/v1/workspaces/native-buildserver'
    data = b'working-copy region state\n'
    file = {'path': 'world/region/r.0.0.mca', 'sha256': digest(data), 'size': len(data)}
    c.request(workspace + '/snapshots', 'POST', {'files': [file]}, creator, 403)
    snapshot = c.request(workspace + '/snapshots', 'POST', {'files': [file]}, staff, 201)
    assert len(snapshot['missing']) == 1 and snapshot['missing'][0]['sha256'] == digest(data)
    commit = workspace + '/snapshots/' + str(snapshot['snapshot']) + '/commit'
    c.request(commit, 'POST', token=staff, status=409)
    c.presigned(snapshot['missing'][0]['url'], 'PUT', data)
    assert c.request(commit, 'POST', token=staff)['head'] == snapshot['snapshot']
    head = c.request(workspace + '/head', token=staff)
    assert head['files'][0]['path'] == file['path'] and head['files'][0]['size'] == len(data)
    assert c.presigned(head['files'][0]['url']) == data
    opened = c.request(workspace + '/snapshots', 'POST', {'base': snapshot['snapshot'], 'files': [file]}, staff, 201)
    assert opened['missing'] == []
    c.request(workspace + '/snapshots', 'POST', {'files': [file]}, staff, 409)
    print('Maps: workspace nested DTOs, missing-blob guard, private S3 HEAD/GET/PUT, deduplication and stale-base conflict passed')

    uploaded = c.request(path + '/uploads', 'POST', token=staff)
    c.presigned(uploaded['url'], 'PUT', bundle)
    deriving = c.request(path + '/versions', 'POST', {'uploadId': uploaded['uploadId'],
                         'sourceSha256': digest(bundle), 'derive': True, 'parentVersion': 1}, staff, 201)
    assert deriving['state'] == 'DERIVING' and deriving['deriveAttempt']
    job = eventually(lambda: c.job('service-maps-derive'))
    descriptor = job['spec']['template']['spec']['containers'][0]
    assert descriptor['command'][1:] == ['-Djava.io.tmpdir=/work', '-cp',
        '/deployments/quarkus-app/app/*:/deployments/quarkus-app/lib/boot/*:/deployments/quarkus-app/lib/main/*',
        'gg.grounds.derive.DeriveWorkerMain', '--request-env', 'DERIVE_REQUEST_JSON']
    request = json.loads(next(e['value'] for e in descriptor['env'] if e['name'] == 'DERIVE_REQUEST_JSON'))
    assert request['sourceSha256'] == digest(bundle) and request['attempt'] == deriving['deriveAttempt']
    assert [candidate['channel'] for candidate in request['catalogCandidates']] == ['stable', 'edge']
    c.execute(job)
    def derived():
        version = c.request(path + '/versions/' + str(deriving['version']), token=staff)
        if version['state'] == 'DERIVE_FAILED':
            raise RuntimeError(f'derivation failed: {version}')
        assert version['state'] == 'PUBLISHED', version
        return version
    completed = eventually(derived)
    assert completed['bundleSha256'] and completed['sizeBytes'] > 0
    assert completed['scene']['status'] == 'NONE' and completed['publishedBySub'] == 'derive-worker'
    print('Maps: enabled PackSet HTTPS refresh, Kubernetes derive Job serialization, real JVM derivation, API result reconciliation passed')

    litjob = eventually(lambda: c.job('service-maps-lit'))
    litcontainer = litjob['spec']['template']['spec']['containers'][0]
    litrequest = json.loads(next(e['value'] for e in litcontainer['env'] if e['name'] == 'LIT_REQUEST_JSON'))
    assert litrequest['format'] == 2, litrequest
    litcommand = litcontainer['command']
    assert litcommand == ['java', '-Xmx1g', '-Djava.io.tmpdir=/work', '-cp',
                          '/deployments/lit-worker/lib/*', 'gg.grounds.lit.worker.LitWorkerMainKt']
    c.execute(litjob)
    def lit():
        pinfile = c.s3('/public/pins/stage.json')
        entry = pinfile['maps'][address]
        assert entry.get('lit', {}).get('format') == 2, entry
        return entry['lit']
    litentry = eventually(lit)
    assert litentry['bundleSha256'] != digest(bundle)
    assert c.s3('/public/bundle/sha256/' + litentry['bundleSha256'][:2] + '/' + litentry['bundleSha256'] + '.tar.zst')
    print('Maps: real standalone JVM lighting worker, API result acceptance, S3 promotion and nested lit pin projection passed')

    # Failure contracts use a syntactically invalid archive but a valid source digest.
    upload = c.request(path + '/uploads', 'POST', token=staff)
    bad = b'invalid zstandard source'
    c.presigned(upload['url'], 'PUT', bad)
    failed = c.request(path + '/versions', 'POST', {'uploadId': upload['uploadId'], 'sourceSha256': digest(bad),
                      'derive': True}, staff, 201)
    badjob = c.jobs('service-maps-derive')[-1]
    c.execute(badjob)
    def failure():
        result = c.request(path + '/versions/' + str(failed['version']), token=staff)
        assert result['state'] == 'DERIVE_FAILED', result
        return result
    result = eventually(failure)
    assert result['deriveFailureScope'] == 'CONTENT' and result['deriveRetryable'] is False
    assert result['scene']['problems'][0]['code'] and result['scene']['problems'][0]['message']
    c.request(path + '/versions/' + str(failed['version']) + '/derive/retry', 'POST', token=staff, status=409)
    print('Maps: polymorphic failure result, persisted problems and nonretryable content failure passed')

    # Isolate API deserialization/persistence of populated Scene facts from the JVM's scene compiler.
    version = c.request(path + '/versions', 'POST', {'uploadId': uploaded['uploadId'],
                        'sourceSha256': digest(bundle), 'derive': True}, staff, 201)
    job = c.jobs('service-maps-derive')[-1]
    descriptor = job['spec']['template']['spec']['containers'][0]
    request = json.loads(next(e['value'] for e in descriptor['env'] if e['name'] == 'DERIVE_REQUEST_JSON'))
    scene = {'present': True, 'schemaVersion': '1', 'sha256': digest(b'scene fixture'),
             'assetCatalog': {'id': 'grounds:resourcepacks', 'version': '1.2.3'},
             'actionCatalog': {'id': 'grounds:lobby', 'version': '2.23.0'},
             'requiredActions': ['grounds:queue', 'grounds:teleport']}
    manifest = encoded({'schemaVersion': 1, 'sourceSha256': digest(bundle),
                        'bundleDigestInputs': ['region/r.0.0.mca', 'scene.json'], 'scene': scene})
    result = {key: request[key] for key in ('schemaVersion', 'mapId', 'version', 'attempt', 'sourceSha256')}
    result.update({'kind': 'SUCCESS', 'bundleSha256': digest(bundle), 'bundleSize': len(bundle),
                   'manifestSha256': digest(manifest), 'manifestSize': len(manifest), 'scene': scene})
    # Exhausted Job backoff must permit an explicit retry under a fresh immutable attempt.
    c.request('/contract/complete/' + job['metadata']['name'], 'POST', {'failed': True}, service='control')
    def job_failed():
        response = c.request(path + '/versions/' + str(version['version']), token=staff)
        assert response['state'] == 'DERIVE_FAILED', response
        return response
    rejected = eventually(job_failed)
    assert rejected['deriveFailureScope'] == 'SYSTEM' and rejected['deriveRetryable'] is True
    retried = c.request(path + '/versions/' + str(version['version']) + '/derive/retry', 'POST', token=staff, status=202)
    assert retried['deriveAttempt'] != version['deriveAttempt']
    job = c.jobs('service-maps-derive')[-1]
    descriptor = job['spec']['template']['spec']['containers'][0]
    request = json.loads(next(e['value'] for e in descriptor['env'] if e['name'] == 'DERIVE_REQUEST_JSON'))
    result['attempt'] = request['attempt']
    for key, value in [('bundleUrl', bundle), ('manifestUrl', manifest), ('resultUrl', encoded(result))]:
        c.presigned(request[key], 'PUT', value)
    c.request('/contract/complete/' + job['metadata']['name'], 'POST', {}, service='control')
    def scene_read():
        response = c.request(path + '/versions/' + str(version['version']), token=staff)
        assert response['state'] == 'PUBLISHED', response
        return response['scene']
    stored = eventually(scene_read)
    assert stored['status'] == 'VALID' and stored['assetCatalog'] == scene['assetCatalog']
    assert stored['actionCatalog'] == scene['actionCatalog'] and stored['requiredActionIds'] == scene['requiredActions']
    print('Maps: populated Scene result marker, both catalog references and nonempty required-action persistence passed')
    print('Maps: exhausted worker Job failure is retryable; explicit retry uses a fresh attempt and succeeds')
    assert_prometheus_metrics(c, populated=True)
    print('Maps: unauthenticated Prometheus endpoint exports HTTP and populated derive metrics')


def main():
    worker_image()
    with tempfile.TemporaryDirectory(prefix='maps-contract-') as temporary:
        fixtures = pathlib.Path(temporary)
        fixtures.chmod(0o755)
        command('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(fixtures / 'key.pem'),
                '-out', str(fixtures / 'cert.pem'), '-days', '1', '-subj', '/CN=maps-fixtures',
                '-addext', 'subjectAltName=DNS:maps-fixtures')
        modulus = command('openssl', 'rsa', '-in', str(fixtures / 'key.pem'), '-noout', '-modulus').split('=', 1)[1]
        (fixtures / 'jwks.json').write_bytes(encoded({'keys': [{'kty': 'RSA', 'kid': 'contract', 'use': 'sig', 'alg': 'RS256',
                                                             'n': b64(bytes.fromhex(modulus)), 'e': 'AQAB'}]}))
        command('keytool', '-importcert', '-noprompt', '-alias', 'fixture', '-file', str(fixtures / 'cert.pem'),
                '-keystore', str(fixtures / 'trust.p12'), '-storepass', 'changeit')
        (fixtures / 'trust.p12').chmod(0o644)
        shutil.copyfile(ROOT / 'ci/maps-fixtures.py', fixtures / 'server.py')
        contract = Contract(fixtures)
        try:
            docker('network', 'create', contract.name)
            contract.run('database', 'postgres:17-alpine', env={'POSTGRES_DB': 'maps', 'POSTGRES_USER': 'maps', 'POSTGRES_PASSWORD': 'contract'})
            contract.run('storage', 'rustfs/rustfs:1.0.0',
                         env={'RUSTFS_ACCESS_KEY': 'contract', 'RUSTFS_SECRET_KEY': 'contract-secret'})
            contract.run('maps-fixtures', 'python:3.13-alpine', '-v', str(fixtures) + ':/fixtures:ro',
                         ports=(443, 8080), command_args=('python', '/fixtures/server.py'))
            contract.ports['control', 8080] = contract.ports['maps-fixtures', 8080]
            eventually(lambda: contract.s3('/private', 'PUT'))
            contract.s3('/public', 'PUT')
            contract.s3('/ugc', 'PUT')
            contract.run('broker', 'nats:2.11-alpine', ports=(4222,))
            eventually(lambda: docker('exec', contract.name + '-database', 'pg_isready', '-h', '127.0.0.1', '-U', 'maps', '-d', 'maps'))
            env = {'POSTGRES_URL': 'jdbc:postgresql://database:5432/maps?currentSchema=maps',
                   'POSTGRES_USER': 'maps', 'POSTGRES_PASSWORD': 'contract', 'NATS_URL': 'nats://broker:4222',
                   'GROUNDS_OIDC_SERVER_URL': 'http://maps-fixtures:8080/realm',
                   'MAPS_S3_ENDPOINT': 'https://maps-fixtures', 'MAPS_S3_REGION': 'us-east-1',
                   'MAPS_R2_ACCESS_KEY_ID': 'contract', 'MAPS_R2_SECRET_ACCESS_KEY': 'contract-secret',
                   'MAPS_R2_PRIVATE_BUCKET': 'private', 'MAPS_R2_PUBLIC_BUCKET': 'public', 'MAPS_R2_UGC_BUCKET': 'ugc',
                   'MAPS_DERIVE_ENABLED': 'true', 'MAPS_DERIVE_IMAGE': WORKER_DIGEST,
                   'MAPS_DERIVE_POLL_INTERVAL': '1s', 'MAPS_LIT_POLL_INTERVAL': '1s',
                   'MAPS_CATALOGS_BASE_URI': 'https://maps-fixtures', 'MAPS_CATALOGS_PACK_SET': 'global',
                   'QUARKUS_KUBERNETES_CLIENT_API_SERVER_URL': 'http://maps-fixtures:8080',
                   'QUARKUS_KUBERNETES_CLIENT_TRUST_CERTS': 'true', 'QUARKUS_KUBERNETES_CLIENT_NAMESPACE': 'maps',
                   'JAVA_TOOL_OPTIONS': '-Djavax.net.ssl.trustStore=/fixtures/trust.p12 -Djavax.net.ssl.trustStorePassword=changeit'}
            arguments = () if 'MAPS_CONTRACT_IMAGE' in os.environ else (
                '-Djavax.net.ssl.trustStore=/fixtures/trust.p12', '-Djavax.net.ssl.trustStorePassword=changeit')
            contract.run('api', IMAGE, '-v', str(fixtures) + ':/fixtures:ro', '--read-only', '--tmpfs', '/tmp',
                         ports=(9000,), env=env, command_args=arguments)
            def ready():
                if docker('inspect', '-f', '{{.State.Running}}', contract.name + '-api') != 'true':
                    raise RuntimeError('packaged API exited before becoming ready')
                health = contract.request('/q/health/ready')
                assert health['status'] == 'UP', health
            eventually(ready)
            contracts(contract)
            print('Packaged Maps contract passed: ' + IMAGE)
        except BaseException:
            for name in contract.containers:
                if name.endswith('-api'):
                    print(docker('logs', name, check=False)[-18000:])
            raise
        finally:
            contract.close()


if __name__ == '__main__':
    main()
