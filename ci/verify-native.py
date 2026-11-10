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
import zlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PREFIX = os.environ.get('NATIVE_IMAGE_PREFIX', 'grounds/service-maps')
IMAGE = os.environ.get('MAPS_CONTRACT_IMAGE', PREFIX + ':native-micro-pilot')
WORKER = os.environ.get('MAPS_WORKER_IMAGE', PREFIX + ':jvm-worker-contract')
LOD_RUN_ID = str(uuid.uuid4())
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

    def light_job(self, bundle_sha):
        matching = []
        for job in self.jobs('service-maps-lit'):
            container = job['spec']['template']['spec']['containers'][0]
            request = json.loads(next(e['value'] for e in container['env'] if e['name'] == 'LIT_REQUEST_JSON'))
            if request['bundleSha256'] == bundle_sha:
                matching.append(job)
        assert matching, 'no lighting Job for the exact published bundle'
        return matching[-1]

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

    def sql(self, statement, variables=None, check=True):
        options=[]
        for key,value in (variables or {}).items(): options+=['-v',key+'='+str(value)]
        return docker('exec', '-i', self.name + '-database', 'psql', '-U', 'maps', '-d', 'maps', '-v', 'ON_ERROR_STOP=1', '-At',
                      *options,input=("SET search_path=maps;\n" + statement).encode(),check=check)

    def close(self):
        for name in reversed(self.containers):
            docker('rm', '-f', name, check=False)
        docker('network', 'rm', self.name, check=False)


def source_bundle(populated=False):
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode='w') as tar:
        # An empty Anvil region is a valid world and exercises the real Zstd/archive worker.
        for name, body in [('region/r.0.0.mca', populated_region() if populated else bytes(8192)), ('native-contract.txt', b'packaged maps contract\n')]:
            entry = tarfile.TarInfo(name)
            entry.size = len(body)
            tar.addfile(entry, io.BytesIO(body))
        end = tar.offset
    # The strict worker accepts exactly two terminal records, without tarfile's extra record padding.
    data = archive.getvalue()[:end + 1024]
    # One raw-block Zstandard frame: no optional Python compression dependency in CI.
    return b'\x28\xb5\x2f\xfd\xa0' + struct.pack('<I', len(data)) + ((len(data) << 3) | 1).to_bytes(3, 'little') + data


def populated_region():
    # Ordinary Anvil/NBT, canonical 26.3 block-state identities, one all-stone section.
    def string(value):
        data=value.encode(); return struct.pack('>H',len(data))+data
    def tag(kind,name,body):
        return bytes([kind])+string(name)+body
    def compound(*tags):
        return b''.join(tags)+b'\0'
    states=compound(tag(9,'palette',b'\x0a'+struct.pack('>i',1)+compound(tag(8,'id',string('minecraft:stone')))))
    biomes=compound(tag(9,'palette',b'\x08'+struct.pack('>i',1)+string('minecraft:plains')))
    section=compound(tag(1,'Y',b'\x04'),tag(10,'block_states',states),tag(10,'biomes',biomes))
    nbt=tag(10,'',compound(tag(3,'xPos',struct.pack('>i',0)),tag(3,'zPos',struct.pack('>i',0)),
        tag(3,'yPos',struct.pack('>i',-4)),tag(8,'status',string('minecraft:full')),
        tag(9,'sections',b'\x0a'+struct.pack('>i',1)+section),tag(9,'block_entities',b'\x0a'+struct.pack('>i',0))))
    compressed=zlib.compress(nbt)
    record=struct.pack('>i',len(compressed)+1)+b'\x02'+compressed
    sectors=(len(record)+4095)//4096
    return struct.pack('>I',(2<<8)|sectors)+bytes(8192-4)+record+bytes(sectors*4096-len(record))


def lod_contract(c):
    staff=c.token()
    address='grounds/native-lod-contract'; path='/v1/maps/'+address
    c.request('/v1/maps','POST',{'address':address,'kind':'ARENA'},staff,201)
    source=source_bundle(populated=True); source_sha=digest(source)
    upload=c.request(path+'/uploads','POST',token=staff)
    c.presigned(upload['url'],'PUT',source)
    c.request(path+'/versions','POST',{'uploadId':upload['uploadId'],'sourceSha256':source_sha},staff,201)
    c.request(path+'/versions/1/publish','POST',{'bundleSha256':source_sha,'sizeBytes':len(source),'presentChunks':1,'estLoadedMib':1},staff)
    c.request(path+'/pins/lod-contract','POST',{'version':1},staff)
    def lighting_job():
        for job in c.jobs('service-maps-lit'):
            request=json.loads(next(e['value'] for e in job['spec']['template']['spec']['containers'][0]['env'] if e['name']=='LIT_REQUEST_JSON'))
            if request['bundleSha256']==source_sha:
                return job
        raise AssertionError('populated lighting Job not created')
    c.execute(eventually(lighting_job))
    def prelit_entry():
        entry=c.s3('/public/pins/lod-contract.json')['maps'][address]
        assert entry.get('lit',{}).get('bundleSha256')
        return entry['lit']
    prelit=eventually(prelit_entry)
    identity_output=docker('run','--rm','--entrypoint','java',WORKER,'-Xmx256m','-XX:ActiveProcessorCount=2',
        '-cp','/deployments/lod-worker/lib/*','gg.grounds.lod.worker.LodWorkerMainKt','--identity')
    identity=json.loads(next(line for line in identity_output.splitlines() if line.startswith('{')))
    # A deliberately explicit operator selection. No HTTP request or restart creates budgets/runs.
    def quote(value): return "'"+str(value).replace("'","''")+"'"
    c.sql("INSERT INTO map_lod_budget(id) VALUES ('native-contract');\n"+
        "INSERT INTO map_lod_run(id,budget_id,environment,namespace,name,version,map_bundle_sha256,source_bundle_sha256,registry_sha256,generator_sha256,worker_image,created_at,expires_at,created_by) VALUES ("+
        ','.join(map(quote,[LOD_RUN_ID,'native-contract','lod-contract','grounds','native-lod-contract']))+",1,"+
        ','.join(map(quote,[source_sha,prelit['bundleSha256'],identity['registrySha256'],identity['generatorSha256'],WORKER_DIGEST]))+
        ",now(),now()+interval '30 minutes','contract-operator');")
    def activated_job():
        job=c.job('service-maps-lod')
        assert job['spec']['suspend'] is False
        assert job['spec']['backoffLimit']==0 and job['spec']['activeDeadlineSeconds']==900
        return job
    job=eventually(activated_job)
    c.execute(job)
    def lod_entry():
        entry=c.s3('/public/pins/lod-contract.json')['maps'][address]
        assert entry.get('lod',{}).get('artifacts')
        return entry['lod']['artifacts'][0]
    artifact=eventually(lod_entry)
    assert artifact['format']=='lss20' and artifact['sourceBundleSha256']==prelit['bundleSha256']
    manifest=c.s3('/public/'+urllib.parse.urlsplit(artifact['manifestUrl']).path.lstrip('/'))
    assert manifest['columns']==1 and manifest['generatorSha256']==identity['generatorSha256']
    terrain=c.s3('/public/'+urllib.parse.urlsplit(artifact['bundleUrl']).path.lstrip('/'))
    assert digest(terrain)==artifact['bundleSha256'] and len(terrain)==artifact['bundleBytes']
    with zipfile.ZipFile(io.BytesIO(terrain)) as archive:
        assert digest(archive.read('manifest.json'))==artifact['manifestSha256']
        for tile in manifest['tiles']:
            assert digest(archive.read(tile['path']))==tile['sha256']
    assert len(c.jobs('service-maps-lod'))==1
    assert c.sql("SELECT jobs_reserved FROM map_lod_budget WHERE id='native-contract';").splitlines()[-1]=='1'
    # Private one-use relay, not a reusable presigned R2 URL. Exercise Native response DTOs/streaming.
    serving_id, player, request_id = (str(uuid.uuid4()) for _ in range(3))
    capability = 'local-contract-capability-' + uuid.uuid4().hex
    attempt = c.sql(f"SELECT attempt FROM map_lod WHERE run_id='{LOD_RUN_ID}' AND state='READY';").splitlines()[-1]
    c.sql("INSERT INTO map_lod_serving_run(id,artifact_attempt,token_sha256,allowed_players,starts_at,expires_at,created_by) VALUES ("+
          ','.join(map(quote,[serving_id,attempt,digest(capability.encode())]))+
          f",ARRAY['{player}'::uuid],now(),now()+interval '30 minutes','contract-operator');")
    control = f'/v1/maps/lod-pilots/{serving_id}'
    headers = {'X-Grounds-Lod-Pilot': capability}
    request = {'schemaVersion':1,'requestId':request_id,'mapBundleSha256':source_sha,'playerId':player}
    c.request(control+'/source','POST',request,status=403)
    source_grant = c.request(control+'/source','POST',request,headers=headers)
    assert source_grant['bundleUrl'] == control+'/source/'+request_id
    assert source_grant['bundleSha256'] == artifact['bundleSha256']
    c.request(source_grant['bundleUrl'],'HEAD',headers=headers,status=405)
    c.request(source_grant['bundleUrl'],headers={**headers,'Range':'bytes=0-1'},status=416)
    assert c.request(source_grant['bundleUrl'],headers=headers) == terrain
    c.request(source_grant['bundleUrl'],headers=headers,status=410)
    c.request(control+'/source','POST',request,headers=headers,status=409)
    assert c.sql(f"SELECT remote_reads_reserved FROM map_lod_serving_run WHERE id='{serving_id}';").splitlines()[-1]=='2'
    credit=c.request(control+'/credits','POST',{**request,'requestId':str(uuid.uuid4()),'bytes':262144},headers=headers)
    assert credit['bytes']==262144 and 0<credit['validForMillis']<=1000
    c.sql(f"UPDATE map_lod_serving_run SET enabled=false WHERE id='{serving_id}';")
    c.request(control+'/credits','POST',{**request,'requestId':str(uuid.uuid4()),'bytes':1},headers=headers,status=410)
    print('Maps: Native one-use private relay, replay/HEAD/Range refusal, global debits and persistent kill switch passed')
    print('Maps: real LOD worker, suspended/UID-fenced admission, Native ZIP/manifest acceptance, immutable source-pinned LOD projection and permanent reservation passed')


def operator_registration_contract(c):
    map_id=str(uuid.uuid4()); attempt=str(uuid.uuid4())
    canonical='a2aa86931891165e39111461f7f664264260379ce184a4f2b1a8f4f158456f84'
    prelit='8c0303b517afec90d31dbb581bd627cdb3a9a348770eae8bb24af7d1a3b37c92'
    c.sql(f"INSERT INTO map(id,namespace,name,display_name,kind,trust,owner_sub) VALUES ('{map_id}','lobby','mainlobby','fixture','ARENA','FIRST_PARTY','fixture');"+
          f"INSERT INTO map_version(map,version,state,bundle_sha256,published_by_sub,scene_present) VALUES ('{map_id}',13,'PUBLISHED','{canonical}','fixture',false);"+
          f"INSERT INTO map_pin(environment,map,version,moved_by_sub) VALUES ('stage','{map_id}',13,'fixture');"+
          f"INSERT INTO map_lit(bundle_sha256,light_format,state,attempt,lit_sha256,lit_size) VALUES ('{canonical}',1,'LIT','{attempt}','{'f'*64}',1);")
    script=(ROOT/'scripts/register-lod-pilot.sql').read_text()
    variables={'run_id':str(uuid.uuid4()),'worker_image':WORKER_DIGEST,'generator_sha256':'c'*64,'registry_sha256':'d'*64}
    refused=c.sql(script,variables,check=False)
    assert 'LOD selection refused' in refused
    assert c.sql("SELECT count(*) FROM map_lod_budget WHERE id='lobby-v13-lss20-v1';").splitlines()[-1]=='0'
    c.sql(f"UPDATE map_lit SET lit_sha256='{prelit}' WHERE bundle_sha256='{canonical}';")
    registered=c.sql(script,variables)
    assert 'Registered the exact Lobby v13 selection' in registered
    c.sql("UPDATE map_lod_budget SET jobs_reserved=1,bytes_reserved=270598144 WHERE id='lobby-v13-lss20-v1';")
    variables['run_id']=str(uuid.uuid4())
    c.sql(script,variables)
    assert c.sql("SELECT jobs_reserved FROM map_lod_budget WHERE id='lobby-v13-lss20-v1';").splitlines()[-1]=='1'
    assert len(c.jobs('service-maps-lod'))==1, 'registering a different run cannot silently change the admitted run'
    serving_script=(ROOT/'scripts/register-lod-serving-pilot.sql').read_text()
    serving_attempt, serving_id, serving_player=(str(uuid.uuid4()) for _ in range(3))
    serving_vars={'run_id':serving_id,'artifact_attempt':serving_attempt,'token_sha256':'a'*64,'players':'{'+serving_player+'}'}
    assert 'LOD serving selection refused' in c.sql(serving_script,serving_vars,check=False)
    c.sql("INSERT INTO map_lod(attempt,run_id,identity_sha256,trust,map_bundle_sha256,source_bundle_sha256,registry_sha256,generator_sha256,worker_image,state,started_at,updated_at,bundle_sha256,bundle_bytes,manifest_sha256,manifest_bytes,columns) VALUES ("+
          f"'{serving_attempt}','{variables['run_id']}','{'7'*64}','FIRST_PARTY','{canonical}','{prelit}','{'d'*64}','{'c'*64}','{WORKER_DIGEST}','READY',now(),now(),'{'8'*64}',4096,'{'9'*64}',256,1);")
    assert 'Registered a 30-minute private Lobby pilot' in c.sql(serving_script,serving_vars)
    c.sql(f"UPDATE map_lod_serving_run SET remote_reads_reserved=10 WHERE id='{serving_id}';")
    assert 'ERROR' in c.sql(serving_script,serving_vars,check=False)
    assert c.sql(f"SELECT remote_reads_reserved FROM map_lod_serving_run WHERE id='{serving_id}';").splitlines()[-1]=='10'
    print('Maps: serving operator script refuses absent artifact and cannot reset an existing pilot passed')
    print('Maps: exact Lobby operator registration refuses changed input, rolls back empty selection and never resets an existing budget passed')


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

    def ready_version(number):
        value = c.request(path + '/versions/' + str(number), token=staff)
        assert value['preparation']['lightFormat'] == 2, value
        assert value['preparation']['stage'] == 'READY', value
        return value

    initial = c.request(path + '/versions/1', token=staff)
    assert initial['preparation']['stage'] in ('WAITING_FOR_LIGHT', 'LIGHTING'), initial
    c.request(path + '/pins/stage', 'POST', {'version': 1}, staff, 409)
    c.execute(eventually(lambda: c.light_job(digest(bundle))))
    eventually(lambda: ready_version(1))
    print('Maps: published unpinned bundle is lit, exact-version status becomes READY, premature live pin refused')

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
        assert entry['version'] == 1 and entry['bundleSha256'] == digest(bundle) and entry['lit']['format'] == 2
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
    creator_fork = '/v1/maps/u/creator/prepared-fork'
    c.request(path + '/forks', 'POST', {'target': 'u/creator/prepared-fork', 'fromVersion': 1}, creator, 201)
    copied = c.request(creator_fork + '/versions/1', token=creator)
    assert copied['preparation']['stage'] == 'READY', copied
    c.request(creator_fork + '/versions/1', token=other, status=404)
    raw_key = '/bundle/sha256/' + digest(bundle)[:2] + '/' + digest(bundle) + '.tar.zst'
    assert c.s3('/ugc' + raw_key) == bundle
    lit_sha = entry['lit']['bundleSha256']
    lit_key = '/bundle/sha256/' + lit_sha[:2] + '/' + lit_sha + '.tar.zst'
    assert c.s3('/ugc' + lit_key) == c.s3('/public' + lit_key)
    assert len(c.jobs('service-maps-lit')) == 1, 'forks must reuse their accepted lighting copy'
    print('Maps: cross-trust fork retains raw and lit availability without another worker; unauthorized status stays private')
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
    assert [candidate['channel'] for candidate in request['catalogCandidates']] == ['stable', 'edge'] + ['pinned'] * 3
    assert [candidate['version'] for candidate in request['catalogCandidates'] if candidate['channel'] == 'pinned'] == [
        f'0.0.0-retained.{i}' for i in range(3)]
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

    assert completed['preparation']['stage'] in ('WAITING_FOR_LIGHT', 'LIGHTING'), completed
    c.request(path + '/pins/stage', 'POST', {'version': deriving['version']}, staff, 409)
    assert c.request(path + '/pins', token=staff)[0]['version'] == 1
    litjob = eventually(lambda: c.light_job(completed['bundleSha256']))
    litcontainer = litjob['spec']['template']['spec']['containers'][0]
    litrequest = json.loads(next(e['value'] for e in litcontainer['env'] if e['name'] == 'LIT_REQUEST_JSON'))
    assert litrequest['format'] == 2, litrequest
    litcommand = litcontainer['command']
    assert litcommand == ['java', '-Xmx1g', '-Djava.io.tmpdir=/work', '-cp',
                          '/deployments/lit-worker/lib/*', 'gg.grounds.lit.worker.LitWorkerMainKt']
    c.execute(litjob)
    eventually(lambda: ready_version(deriving['version']))
    c.request(path + '/pins/stage', 'POST', {'version': deriving['version']}, staff)
    def lit():
        pinfile = c.s3('/public/pins/stage.json')
        entry = pinfile['maps'][address]
        assert entry['version'] == deriving['version'], entry
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
            # Retain the editor snapshot alongside the catalogs of two published versions.
            # Startup must accept this configuration in the actual native artifact.
            retained_catalogs = [dict(
                channel='pinned', id='grounds:resourcepacks', version=f'0.0.0-retained.{i}',
                coordinate=f'gg.grounds:resourcepacks-catalog:0.0.0-retained.{i}',
                file='catalog.jar', sha256=f'{i + 1:064x}', size=1,
                uri=f'https://maps-fixtures/resourcepacks/catalog-pins/contract/0.0.0-retained.{i}/{i + 1:064x}/catalog.jar',
            ) for i in range(3)]
            env = {'POSTGRES_URL': 'jdbc:postgresql://database:5432/maps?currentSchema=maps',
                   'POSTGRES_USER': 'maps', 'POSTGRES_PASSWORD': 'contract', 'NATS_URL': 'nats://broker:4222',
                   'GROUNDS_OIDC_SERVER_URL': 'http://maps-fixtures:8080/realm',
                   'MAPS_S3_ENDPOINT': 'https://maps-fixtures', 'MAPS_S3_REGION': 'us-east-1',
                   'MAPS_R2_ACCESS_KEY_ID': 'contract', 'MAPS_R2_SECRET_ACCESS_KEY': 'contract-secret',
                   'MAPS_R2_PRIVATE_BUCKET': 'private', 'MAPS_R2_PUBLIC_BUCKET': 'public', 'MAPS_R2_UGC_BUCKET': 'ugc',
                   'MAPS_DERIVE_ENABLED': 'true', 'MAPS_DERIVE_IMAGE': WORKER_DIGEST,
                   'MAPS_LOD_ENABLED': 'true', 'MAPS_LOD_PUBLICATION_ENABLED': 'true', 'MAPS_LOD_PILOT_ENABLED': 'true',
                   'MAPS_LOD_RUN_ID': LOD_RUN_ID, 'MAPS_LOD_NODE': 'contract-node', 'MAPS_LOD_POLL_INTERVAL': '1s',
                   'MAPS_DERIVE_POLL_INTERVAL': '1s', 'MAPS_LIT_POLL_INTERVAL': '1s',
                   'MAPS_CATALOGS_BASE_URI': 'https://maps-fixtures', 'MAPS_CATALOGS_PACK_SET': 'global',
                   'MAPS_CATALOGS_PINNED_JSON': json.dumps(retained_catalogs),
                   'QUARKUS_KUBERNETES_CLIENT_API_SERVER_URL': 'http://maps-fixtures:8080',
                   'QUARKUS_KUBERNETES_CLIENT_TRUST_CERTS': 'true', 'QUARKUS_KUBERNETES_CLIENT_NAMESPACE': 'maps',
                   'JAVA_TOOL_OPTIONS': '-Djavax.net.ssl.trustStore=/fixtures/trust.p12 -Djavax.net.ssl.trustStorePassword=changeit'}
            entrypoint=json.loads(docker('image','inspect',IMAGE,'--format','{{json .Config.Entrypoint}}'))
            arguments = () if pathlib.Path(entrypoint[0]).name == 'java' else (
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
            lod_contract(contract)
            operator_registration_contract(contract)
            # Exercise the packaged pilot process itself, including internal/direct requests.
            docker('stop', contract.name + '-api')
            isolated = {**env, 'MAPS_DERIVE_ENABLED': 'false', 'MAPS_LIT_ENABLED': 'false',
                        'MAPS_LOD_ENABLED': 'false', 'MAPS_LOD_PUBLICATION_ENABLED': 'false',
                        'MAPS_LOD_PILOT_ISOLATED': 'true',
                        'MAPS_LOD_PILOT_OWNER_POD_NAME': 'native-pilot',
                        'MAPS_LOD_PILOT_OWNER_POD_UID': '321ba5f0-b4f5-46bc-865c-d8013e2a552d'}
            contract.run('isolated', IMAGE, '-v', str(fixtures) + ':/fixtures:ro', '--read-only', '--tmpfs', '/tmp',
                         ports=(9000,), env=isolated, command_args=arguments)
            contract.ports['api', 9000] = contract.ports['isolated', 9000]
            eventually(lambda: contract.request('/q/health/ready'))
            pins = contract.s3('/public/pins/stage.json')
            staff = contract.token()
            contract.request('/v1/maps/grounds/native-contract/pins/stage', 'POST', {'version': 1}, staff, 404)
            contract.request('/v1/maps', 'POST', token=staff, raw=b'{', status=404)
            contract.request('/v1/maps/lod-pilots/castle', token=staff, status=404)
            assert contract.s3('/public/pins/stage.json') == pins, 'isolated pilot changed ordinary pins'
            contract.request('/v1/maps/lod-pilots/' + str(uuid.uuid4()) + '/credits', 'POST', raw=b'{', status=403)
            print('Maps: isolated Native direct/staff write denial, unchanged public pins and protected private operation passed')
            print('Packaged Maps contract passed: ' + IMAGE)
        except BaseException:
            for name in contract.containers:
                if name.endswith(('-api', '-isolated')):
                    print(docker('logs', name, check=False)[-18000:])
            raise
        finally:
            contract.close()


if __name__ == '__main__':
    main()
