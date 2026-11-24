#!/usr/bin/env python3
"""Read protected operational metrics; keep rotating bearer tokens outside Git."""
import concurrent.futures, http.server, json, os, pathlib, threading, time, urllib.parse, urllib.request
ORIGINS = json.loads(os.environ['BOODOO_MONITOR_ORIGINS_JSON'])
TOKENS = pathlib.Path(os.environ['BOODOO_MONITOR_TOKEN_FILE'])
NAMES = ['boodoo.transfers.pending','boodoo.transfers.oldest.seconds','boodoo.webhooks.dead','boodoo.webhooks.pending','boodoo.reconciliation.discrepancies']
for service, origin in ORIGINS.items():
    parsed = urllib.parse.urlsplit(origin)
    if not service.replace('-', '').isalnum() or parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username or parsed.query or parsed.fragment or parsed.path not in ('','/'):
        raise ValueError('Monitor origins must be trusted HTTP(S) origins')
SAMPLES = '# No completed probe yet\n'
LOCK = threading.Lock()

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):
        return None
OPENER=urllib.request.build_opener(NoRedirect)

def read(url, bearer=None):
    request=urllib.request.Request(url,headers={} if bearer is None else {'Authorization':'Bearer '+bearer})
    with OPENER.open(request,timeout=2) as response:
        body=response.read(65537)
        if len(body)>65536:
            raise ValueError('Oversized metric response')
        return json.loads(body)

def probe(item, tokens):
    service,origin=item
    labels='{service="'+service+'"}'
    result=[]
    try:
        up=read(origin+'/actuator/health').get('status')=='UP'
    except Exception:
        up=False
    result.append('boodoo_service_up'+labels+' '+str(int(up)))
    readable=False
    if service in tokens:
        try:
            available=set(read(origin+'/actuator/metrics',tokens[service]).get('names',[]))
            readable=True
            for name in NAMES:
                if name in available:
                    value=read(origin+'/actuator/metrics/'+name,tokens[service])['measurements'][0]['value']
                    if not isinstance(value,(int,float)):
                        raise ValueError('Invalid measurement')
                    result.append(name.replace('.','_')+labels+' '+str(value))
        except Exception:
            readable=False
    result.append('boodoo_metrics_readable'+labels+' '+str(int(readable)))
    return '\n'.join(result)

def poll():
    global SAMPLES
    while True:
        try:
            tokens=json.loads(TOKENS.read_text())
        except Exception:
            tokens={}
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            samples=list(pool.map(lambda item:probe(item,tokens),ORIGINS.items()))
        with LOCK:
            SAMPLES='\n'.join(samples)+'\nboodoo_probe_timestamp_seconds '+str(time.time())+'\n'
        time.sleep(15)

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):
        pass
    def do_GET(self):
        if self.path!='/metrics':
            self.send_error(404)
            return
        with LOCK:
            body=SAMPLES.encode()
        self.send_response(200)
        self.send_header('Content-Type','text/plain; version=0.0.4')
        self.send_header('Content-Length',str(len(body)))
        self.end_headers()
        self.wfile.write(body)

if __name__=='__main__':
    threading.Thread(target=poll,daemon=True).start()
    http.server.ThreadingHTTPServer((os.environ.get('BOODOO_MONITOR_BIND','127.0.0.1'),int(os.environ.get('BOODOO_MONITOR_PORT','9109'))),Handler).serve_forever()
