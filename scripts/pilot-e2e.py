#!/usr/bin/env python3
"""Run isolated real-service tests. Requires built jars, Docker, Java 25+ and OpenSSL."""
import base64, concurrent.futures, hashlib, hmac, http.server, json, os, pathlib, secrets, socket, subprocess, tempfile, threading, time, urllib.error, urllib.parse, urllib.request, uuid
ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULES = sorted(p.name for p in (ROOT / 'services').iterdir() if (p / 'pom.xml').exists() and p.name != 'support-service')
SCOPES = '''customers:write customers:read customers:ownership:read customers:kyc:read kyc:submit kyc:read kyc:review kyc:lifecycle pins:create pins:read pins:verify wallets:write wallets:read wallets:activate wallets:eligibility:read wallets:control ledger:accounts:write ledger:accounts:read ledger:journals:write ledger:journals:read ledger:balances:read ledger:reconciliation:read ledger:journals:cancel ledger:post:transfer ledger:post:settlement ledger:post:adjustment ledger:controls:read ledger:controls:write ledger:statements:read ledger:reversals:request ledger:reversals:approve ledger:reversals:read transfers:write transfers:read transfers:resume transfers:cancel pricing:quotes:write pricing:quotes:read limits:reservations:write limits:reservations:read limits:reservations:settle risk:evaluations:write risk:evaluations:read audit:events:write audit:events:read notifications:write notifications:read notifications:ack notifications:delivery:read notifications:delivery:retry reconciliation:checks:write reconciliation:checks:read reconciliation:statements:write reconciliation:statements:read reconciliation:investigate cash:request cash:approve cash:read cash:disburse merchants:write merchants:read merchants:control payments:write operations:metrics:read'''.split()
AUDIENCES = ['boodoo-' + (m.removesuffix('-service') if m != 'api-gateway' else 'gateway') + '-api' for m in MODULES]

def command(*args, data=None, env=None):
    result = subprocess.run(args, input=data, env=env, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError('Command failed: ' + args[0])
    return result.stdout

def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()

def der_item(data, offset=0):
    tag, length, start = data[offset], data[offset + 1], offset + 2
    if length & 128:
        n = length & 127
        length, start = int.from_bytes(data[start:start+n], 'big'), start+n
    return tag, data[start:start+length], start+length

class Stack:
    def __init__(self):
        self.temp = pathlib.Path(tempfile.mkdtemp(prefix='boodoo-pilot-e2e-'))
        self.processes, self.logs, self.envs = {}, {}, {}
        self.ports = {m: free_port() for m in MODULES}
        self.database_password = secrets.token_hex(24)
        self.admin_password = secrets.token_hex(24)
        self.pepper = base64.b64encode(secrets.token_bytes(32)).decode()
        self.webhook_secret = secrets.token_bytes(32)
        self.received, self.dropped = set(), False
        self.lock = threading.Lock()
        self.container = 'boodoo-pilot-test-' + uuid.uuid4().hex[:12]
        self.key = self.temp / 'signing.pem'
        command('openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(self.key))
        self.key.chmod(0o600)
        public = command('openssl', 'rsa', '-in', str(self.key), '-RSAPublicKey_out', '-outform', 'DER')
        _, sequence, _ = der_item(public)
        _, modulus, end = der_item(sequence)
        _, exponent, _ = der_item(sequence, end)
        self.jwks = {'keys': [{'kty':'RSA','use':'sig','alg':'RS256','kid':'stack-test','n':b64(modulus.lstrip(b'\0')),'e':b64(exponent)}]}
        stack = self
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def send_json(self, status, body):
                payload = json.dumps(body).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            def do_GET(self):
                if self.path == '/jwks':
                    self.send_json(200, stack.jwks)
                elif self.path.startswith('/internal/'):
                    self.proxy(b'')
                else:
                    self.send_json(404, {})
            def do_POST(self):
                payload = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                if self.path == '/token':
                    expected = 'Basic ' + base64.b64encode(b'transfer-client:fixture-secret').decode()
                    if self.headers.get('Authorization') != expected:
                        self.send_json(401, {})
                        return
                    scopes = urllib.parse.parse_qs(payload.decode()).get('scope', [''])[0].split()
                    if not set(scopes) <= set(SCOPES):
                        self.send_json(400, {})
                        return
                    self.send_json(200, {'access_token': stack.jwt('transfer-service', scopes=scopes), 'token_type': 'Bearer'})
                elif self.path == '/receiver':
                    event = self.headers.get('X-Boodoo-Delivery-Id', '')
                    timestamp = self.headers.get('X-Boodoo-Timestamp', '')
                    expected = 'v1=' + hmac.new(stack.webhook_secret, timestamp.encode()+b'.'+event.encode()+b'.'+payload, hashlib.sha256).hexdigest()
                    if not hmac.compare_digest(self.headers.get('X-Boodoo-Signature', ''), expected):
                        self.send_json(403, {})
                        return
                    with stack.lock:
                        stack.received.add(event)
                    self.send_response(204)
                    self.end_headers()
                elif self.path.startswith('/internal/'):
                    self.proxy(payload)
                else:
                    self.send_json(404, {})
            def proxy(self, payload):
                target = stack.url('ledger-service') + self.path
                headers = {k:v for k,v in self.headers.items() if k.lower() in ('authorization','content-type','idempotency-key')}
                request = urllib.request.Request(target, data=payload if self.command == 'POST' else None, headers=headers, method=self.command)
                try:
                    with urllib.request.urlopen(request, timeout=8) as upstream:
                        status, body = upstream.status, upstream.read()
                except urllib.error.HTTPError as upstream:
                    status, body = upstream.code, upstream.read()
                with stack.lock:
                    drop = self.command == 'POST' and self.path == '/internal/v1/journals' and status == 201 and not stack.dropped
                    if drop:
                        stack.dropped = True
                if drop:
                    self.send_json(503, {'code': 'SIMULATED_LOST_ACK_AFTER_REAL_COMMIT'})
                    return
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.issuer = 'http://127.0.0.1:' + str(self.server.server_port)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def jwt(self, subject='integration-service', role='service', tenant='pilot-test', scopes=None):
        claims = {'iss':self.issuer,'sub':subject,'aud':AUDIENCES,'exp':int(time.time())+1800,'iat':int(time.time()),'integrator_id':tenant,'actor_type':role,'scope':' '.join(SCOPES if scopes is None else scopes)}
        unsigned = b64(json.dumps({'alg':'RS256','kid':'stack-test','typ':'JWT'}).encode()) + '.' + b64(json.dumps(claims).encode())
        signature = command('openssl','dgst','-sha256','-sign',str(self.key),data=unsigned.encode())
        return unsigned+'.'+b64(signature)

    def url(self, module):
        return 'http://127.0.0.1:'+str(self.ports[module])

    def call(self, module, method, path, body=None, token=None, key=None, headers=None, expected=(200,)):
        supplied = {'Authorization':'Bearer '+(token or self.jwt()), **(headers or {})}
        if key:
            supplied['Idempotency-Key'] = key
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            supplied['Content-Type'] = 'application/json'
        if method == 'POST' and data is None:
            data = b''
        request = urllib.request.Request(self.url(module)+path, data=data, headers=supplied, method=method)
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                status, raw, received = response.status, response.read(), response.headers
        except urllib.error.HTTPError as response:
            status, raw, received = response.code, response.read(), response.headers
        result = json.loads(raw) if raw else None
        if status not in expected:
            code = result.get('code', 'HTTP_ERROR') if isinstance(result, dict) else 'HTTP_ERROR'
            raise AssertionError(f'{module} {method} {path}: status {status}, code {code}')
        return status, result, received

    def sql(self, database, statement):
        return command('docker','exec','-e','PGPASSWORD='+self.admin_password,self.container,'psql','-X','-U','postgres','-d',database,'-A','-t','-v','ON_ERROR_STOP=1','-c',statement).decode().strip()

    def start_database(self):
        command('docker','run','--detach','--name',self.container,'-e','POSTGRES_PASSWORD='+self.admin_password,'-p','127.0.0.1::5432','postgres:17')
        for _ in range(80):
            if subprocess.run(['docker','exec',self.container,'pg_isready','-U','postgres'],capture_output=True).returncode == 0:
                break
            time.sleep(.25)
        else:
            raise RuntimeError('Isolated database did not start')
        mapping=json.loads(command('docker','inspect',self.container))[0]['NetworkSettings']['Ports']['5432/tcp'][0]
        self.database_port=mapping['HostPort']
        for module in MODULES:
            if module == 'api-gateway':
                continue
            name=module.removesuffix('-service').replace('-','_')
            self.sql('postgres',f"CREATE ROLE {name}_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{self.database_password}'")
            self.sql('postgres',f'CREATE DATABASE {name}_db OWNER {name}_user')

    def environment(self, module):
        env=os.environ.copy()
        prefix=module.removesuffix('-service').upper().replace('-','_')
        name=module.removesuffix('-service').replace('-','_')
        env.update({prefix+'_SERVER_PORT':str(self.ports[module]),prefix+'_JWT_ISSUER_URI':self.issuer,prefix+'_JWT_JWK_SET_URI':self.issuer+'/jwks',prefix+'_DB_URL':f'jdbc:postgresql://127.0.0.1:{self.database_port}/{name}_db',prefix+'_DB_USER':name+'_user',prefix+'_DB_PASSWORD':self.database_password,'WALLET_SERVICE_TOKENS_JSON':json.dumps({'pilot-test':self.jwt('wallet-service')}),'IDENTITY_PIN_PEPPER':self.pepper,'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE':'health,info,metrics','PRICING_POLICY_VERSION':'fixture','PRICING_BASIS_POINTS':'0','PRICING_FLAT_FEE':'3','PRICING_QUOTE_TTL_SECONDS':'300','LIMITS_POLICY_VERSION':'fixture','LIMITS_PER_TRANSACTION':'1000000','LIMITS_DAILY':'10000000','LIMITS_MONTHLY':'100000000','LIMITS_RESERVATION_TTL_SECONDS':'300','RISK_POLICY_VERSION':'fixture','RISK_AUTOMATIC_AMOUNT':'1000000','RISK_EVALUATION_TTL_SECONDS':'60','RISK_BLOCKED_CUSTOMERS':'','BOODOO_RECOVERY_ENABLED':'false','NOTIFICATION_WEBHOOK_ENABLED':'true','NOTIFICATION_WEBHOOK_DESTINATIONS_JSON':json.dumps({'pilot-test':{'endpoint':self.issuer+'/receiver','version':'fixture','keyId':'fixture','secretBase64':base64.b64encode(self.webhook_secret).decode()}}),'DISTRIBUTION_ZERO_FEE_CASH_ENABLED':'true'})
        for target in ['wallet','customer','identity','ledger','pricing','limits','risk','audit','notification','transfer']:
            destination='risk-compliance-service' if target=='risk' else target+'-service'
            env[prefix+'_'+target.upper()+'_BASE_URL']=self.url(destination)
        if module=='transfer-service':
            scopes=[s for s in SCOPES if s not in ('ledger:post:adjustment','ledger:post:settlement')]
            env['TRANSFER_LEDGER_BASE_URL']=self.issuer
            env['TRANSFER_OAUTH_CLIENTS_JSON']=json.dumps({'pilot-test':{'tokenEndpoint':self.issuer+'/token','clientId':'transfer-client','clientSecret':'fixture-secret','scope':' '.join(scopes),'audiences':AUDIENCES}})
            env['TRANSFER_FEE_ACCOUNTS_JSON']=json.dumps({'pilot-test':self.fee_account})
        if module=='distribution-service':
            env['DISTRIBUTION_SERVICE_TOKENS_JSON']=json.dumps({'pilot-test':self.jwt('distribution-service',scopes=[s for s in SCOPES if s!='ledger:post:adjustment'])})
            env['DISTRIBUTION_SETTLEMENT_ACCOUNTS_JSON']=json.dumps({'pilot-test':self.cash_account})
        return env

    def launch(self, module, restore=False):
        env=self.environment(module)
        if restore and module!='api-gateway':
            env[module.removesuffix('-service').upper().replace('-','_')+'_DB_URL']=env[module.removesuffix('-service').upper().replace('-','_')+'_DB_URL']+'_restore'
        jars=list((ROOT/'services'/module/'target').glob('*.jar'))
        if len(jars)!=1:
            raise RuntimeError('Build exactly one executable jar for '+module)
        log=(self.temp/(module+('-restored' if restore else '')+'.log')).open('wb')
        self.logs[module]=log
        self.processes[module]=subprocess.Popen(['java','-Xmx192m','-XX:ActiveProcessorCount=2','-jar',str(jars[0])],env=env,stdout=log,stderr=subprocess.STDOUT)

    def healthy(self, modules):
        deadline=time.monotonic()+180
        for module in modules:
            while time.monotonic()<deadline:
                if self.processes[module].poll() is not None:
                    raise RuntimeError(module+' stopped; inspect isolated logs at '+str(self.temp))
                try:
                    with urllib.request.urlopen(self.url(module)+'/actuator/health',timeout=2) as response:
                        if response.status==200:
                            break
                except (OSError,urllib.error.URLError):
                    time.sleep(.2)
            else:
                raise RuntimeError('Health deadline exceeded for '+module)

    def stop_services(self):
        for process in self.processes.values():
            if process.poll() is None:
                process.terminate()
        for process in self.processes.values():
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        self.processes.clear()
        for log in self.logs.values():
            log.close()
        self.logs.clear()

    def onboard(self, number):
        gateway='api-gateway'
        _,customer,_=self.call(gateway,'POST','/v1/customers',{'externalReference':f'fixture-{number}','firstName':'Test','lastName':'Customer','phoneNumber':f'+22370000{number:03d}'},key=f'customer-{number}',expected=(201,))
        customer=customer['id']
        _,profile,headers=self.call(gateway,'GET','/v1/customers/'+customer)
        _,profile,headers=self.call(gateway,'PATCH','/v1/customers/'+customer,{'dateOfBirth':'1990-01-01','nationality':'ML','residenceCountry':'ML','addressLine':'Synthetic test address','city':'Bamako'},headers={'If-Match':headers['ETag']})
        _,submission,_=self.call(gateway,'POST',f'/v1/customers/{customer}/kyc-submissions',{'documents':[{'type':'NATIONAL_ID','reference':f'FIXTURE_{number}','issuingCountry':'ML','expiresOn':'2099-12-31'}]},key=f'kyc-{number}',headers={'If-Match':headers['ETag']},expected=(201,))
        self.call(gateway,'POST',f'/v1/customers/{customer}/kyc-submissions/{submission["id"]}/decisions',{'decision':'APPROVED','reasonCode':'FIXTURE_REVIEW'},key=f'decision-{number}',token=self.jwt('kyc-checker','operator'),expected=(201,))
        self.call(gateway,'POST',f'/v1/customers/{customer}/pin',{'pin':'739152'},key=f'pin-{number}',expected=(201,))
        _,wallet,_=self.call(gateway,'POST','/v1/wallets',{'customerId':customer,'currency':'XOF'},key=f'wallet-{number}',expected=(201,))
        _,active,_=self.call('wallet-service','POST',f'/internal/v1/wallets/{wallet["id"]}/activate',key=f'activate-{number}',expected=(201,))
        return customer,wallet['id'],active['ledgerAccountId']

    def backup_restore(self):
        self.stop_services()
        for script in ['backup-db.sh','restore-db.sh']:
            command('docker','cp',str(ROOT/'scripts'/script),self.container+':/tmp/'+script)
        hashes={}
        for module in MODULES:
            if module=='api-gateway':
                continue
            name=module.removesuffix('-service').replace('-','_')
            database=name+'_db'
            tables=self.sql(database,"SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename").splitlines()
            def digest(db):
                return {table:self.sql(db,f'''SELECT md5(COALESCE(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')) FROM "{table}" t''') for table in tables}
            before=digest(database)
            flags=['docker','exec','-e','PGHOST=127.0.0.1','-e','PGUSER='+name+'_user','-e','PGPASSWORD='+self.database_password]
            command(*flags,'-e','PGDATABASE='+database,self.container,'bash','/tmp/backup-db.sh','/tmp/'+database+'.dump')
            self.sql('postgres',f'CREATE DATABASE {database}_restore OWNER {name}_user')
            command(*flags,'-e','PGDATABASE='+database+'_restore','-e','RESTORE_TO_EMPTY_DATABASE=yes',self.container,'bash','/tmp/restore-db.sh','/tmp/'+database+'.dump')
            assert digest(database+'_restore')==before,'Restored rows differ for '+module
            hashes[module]=len(tables)
        restored=['customer-service','identity-service','ledger-service','transfer-service']
        for module in restored:
            self.launch(module,restore=True)
        self.healthy(restored)
        self.call('identity-service','POST',f'/v1/customers/{self.sender_customer}/pin/verify',{'pin':'739152'},expected=(204,))
        _,balance,_=self.call('ledger-service','GET',f'/internal/v1/accounts/{self.sender_account}/balance')
        assert balance['amountMinorUnits']==self.final_balance
        _,transfer,_=self.call('transfer-service','GET','/v1/transfers/'+self.first_transfer)
        assert transfer['status']=='COMPLETED'
        return hashes

    def run(self):
        self.start_database()
        core=[m for m in MODULES if m not in ('api-gateway','transfer-service','distribution-service')]
        for module in core:
            self.launch(module)
        self.healthy(core)
        for code,kind,attribute in [('CASH_FIXTURE','ASSET','cash_account'),('FEES_FIXTURE','INCOME','fee_account')]:
            _,account,_=self.call('ledger-service','POST','/internal/v1/accounts',{'code':code,'currency':'XOF','type':kind},key=code,expected=(201,))
            setattr(self,attribute,account['id'])
        delayed=['distribution-service','transfer-service','api-gateway']
        for module in delayed:
            self.launch(module)
        self.healthy(delayed)
        self.sender_customer,sender,self.sender_account=self.onboard(1)
        recipient_customer,recipient,recipient_account=self.onboard(2)
        cashier=self.jwt('cashier','operator')
        _,cash,_=self.call('distribution-service','POST','/internal/v1/cash-operations',{'walletId':sender,'direction':'IN','amount':1000,'evidence':'FIXTURE_DEPOSIT_001'},token=cashier,key='deposit',expected=(201,))
        self.call('distribution-service','POST',f'/internal/v1/cash-operations/{cash["id"]}/approval',token=self.jwt('cash-checker','operator'))
        request={'senderWalletId':sender,'recipientWalletId':recipient,'amount':100,'maxFee':10,'currency':'XOF','pin':'739152'}
        _,pending,_=self.call('api-gateway','POST','/v1/transfers',request,key='lost-ack',expected=(202,))
        assert pending['status']=='POSTING' and self.dropped
        self.first_transfer=pending['id']
        _,posted,_=self.call('transfer-service','POST',f'/internal/v1/transfers/{self.first_transfer}/resume',token=self.jwt('transfer-service'),expected=(201,))
        assert posted['status']=='COMPLETED'
        _,replayed,_=self.call('api-gateway','POST','/v1/transfers',request,key='lost-ack',expected=(201,))
        assert replayed['journalId']==posted['journalId']
        def spend(number):
            return self.call('api-gateway','POST','/v1/transfers',{**request,'amount':300},key='race-'+str(number),expected=(201,202))
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            races=list(pool.map(spend,range(4)))
        assert sum(status==201 for status,_,_ in races)==2,'Ledger did not enforce the available balance'
        for status,transfer,_ in races:
            if status==202:
                _,cancelled,_=self.call('api-gateway','POST',f'/v1/transfers/{transfer["id"]}/cancellation')
                assert cancelled['status']=='CANCELLED'
        _,merchant,_=self.call('api-gateway','POST','/v1/merchants',{'id':str(uuid.uuid4()),'walletId':recipient,'code':'FIXTURE_SHOP'},token=self.jwt('merchant-operator','operator'),expected=(201,))
        self.call('api-gateway','POST','/v1/merchant-payments',{'merchantId':merchant['id'],'senderWalletId':sender,'amount':50,'maxFee':10,'pin':'739152'},key='shop-payment',expected=(201,))
        _,reversal,_=self.call('ledger-service','POST',f'/internal/v1/journals/{posted["journalId"]}/reversal',{'reasonCode':'FIXTURE_REFUND'},token=self.jwt('refund-maker','operator'),expected=(201,))
        self.call('ledger-service','POST',f'/internal/v1/reversals/{reversal["id"]}/approval',token=self.jwt('refund-checker','operator'))
        # Physical cash acknowledgement is separate from the prior accounting step.
        _,withdrawal,_=self.call('distribution-service','POST','/internal/v1/cash-operations',{'walletId':sender,'direction':'OUT','amount':100,'evidence':'FIXTURE_PAYOUT_001','pin':'739152'},token=cashier,key='withdrawal',expected=(201,))
        self.call('distribution-service','POST',f'/internal/v1/cash-operations/{withdrawal["id"]}/approval',token=self.jwt('cash-checker','operator'))
        self.call('distribution-service','POST',f'/internal/v1/cash-operations/{withdrawal["id"]}/disbursement',token=cashier)
        # A hard hold must fence the ledger, even when wallet metadata was read earlier.
        control={'id':str(uuid.uuid4()),'state':'FROZEN','expectedVersion':0,'reasonCode':'FIXTURE_HOLD'}
        self.call('wallet-service','POST',f'/internal/v1/wallets/{sender}/control',control,token=self.jwt('wallet-operator','operator'))
        _,eligibility,_=self.call('wallet-service','GET',f'/internal/v1/wallets/{sender}/operational-state')
        assert eligibility['eligible'] is False
        self.call('ledger-service','POST','/internal/v1/journals',{'reference':str(uuid.uuid4()),'entries':[{'accountId':self.sender_account,'side':'DEBIT','amount':1},{'accountId':recipient_account,'side':'CREDIT','amount':1}]},key='frozen-post',expected=(409,))
        self.call('wallet-service','POST',f'/internal/v1/wallets/{sender}/control',{**control,'id':str(uuid.uuid4()),'state':'OPEN','expectedVersion':1},token=self.jwt('wallet-operator','operator'))
        deadline=time.monotonic()+30
        while len(self.received)<2 and time.monotonic()<deadline:
            time.sleep(.2)
        assert len(self.received)>=2,'Signed webhooks did not reach the isolated receiver'
        _,balance,_=self.call('ledger-service','GET',f'/internal/v1/accounts/{self.sender_account}/balance')
        self.final_balance=balance['amountMinorUnits']
        assert self.final_balance==241, 'Principal and fees were not conserved'
        # Recent windows must be incomplete; an empty closed historical period can match.
        now=time.time()
        def instant(epoch):
            return time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime(epoch))
        self.call('reconciliation-service','POST','/internal/v1/external-statements',{'id':str(uuid.uuid4()),'accountId':self.cash_account,'provider':'FIXTURE_BANK','statementReference':'EMPTY_CLOSED_PERIOD','from':instant(now-3600),'to':instant(now-1800),'opening':0,'closing':0,'entries':[]},expected=(201,))
        self.call('api-gateway','GET','/v1/wallets/'+sender,token=self.jwt(tenant='foreign-test'),expected=(404,))
        self.call('api-gateway','POST','/v1/transfers',{**request,'amount':100.5},key='fractional',expected=(400,))
        _,profile,_=self.call('api-gateway','GET','/v1/customers/'+recipient_customer)
        self.call('customer-service','POST',f'/internal/v1/customers/{recipient_customer}/kyc-lifecycle',{'id':str(uuid.uuid4()),'action':'REVOKE','expectedVersion':profile['version'],'reasonCode':'FIXTURE_REVOKED'},token=self.jwt('kyc-operator','operator'))
        _,ineligible,_=self.call('wallet-service','GET',f'/internal/v1/wallets/{recipient}/operational-state')
        assert ineligible['eligible'] is False
        self.call('api-gateway','GET','/actuator/metrics',expected=(403,))
        self.call('api-gateway','GET','/actuator/metrics',token=self.jwt('metrics-collector'),expected=(200,))
        restored=self.backup_restore()
        print(json.dumps({'result':'passed','services':len(MODULES),'realLedgerLostAcknowledgement':True,'concurrentOverspendingPrevented':True,'signedWebhookDelivery':True,'restoredDatabases':restored,'restoredPinAndTransferVerified':True}),flush=True)

    def close(self):
        self.stop_services()
        self.server.shutdown()
        self.server.server_close()
        subprocess.run(['docker','rm','--force',self.container],capture_output=True)
        self.key.unlink(missing_ok=True)

if __name__=='__main__':
    stack=Stack()
    try:
        stack.run()
    finally:
        stack.close()
