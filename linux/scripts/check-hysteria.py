import argparse, http.server, json, pathlib, socket, subprocess, tempfile, threading, time
parser = argparse.ArgumentParser(description="Локальная проверка совместимости Xray и Hysteria 2")
parser.add_argument("--xray", required=True)
parser.add_argument("--converter", required=True)
parser.add_argument("--hysteria", required=True)
parser.add_argument("--obfs", action="store_true")
parser.add_argument("--recovery", action="store_true")
args = parser.parse_args()
workspace = tempfile.TemporaryDirectory(prefix="podbor-hysteria-")
ROOT = pathlib.Path(workspace.name)
def port(kind=socket.SOCK_STREAM):
    with socket.socket(socket.AF_INET,kind) as s:
        s.bind(('127.0.0.1',0)); return s.getsockname()[1]
def write(name,value):
    path=ROOT/name; path.write_text(json.dumps(value)); return path
subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(ROOT/'key.pem'),'-out',str(ROOT/'cert.pem'),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
body=b'podbor-transfer-'*16384
class Http(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self,*args): pass
http=http.server.ThreadingHTTPServer(('127.0.0.1',0),Http)
threading.Thread(target=http.serve_forever,daemon=True).start()
hyport=port(socket.SOCK_DGRAM); socks=port()
server=write('server.json',{'listen':f'127.0.0.1:{hyport}','tls':{'cert':str(ROOT/'cert.pem'),'key':str(ROOT/'key.pem')},'auth':{'type':'password','password':'test-only'}})
if args.obfs:
    settings = json.loads(server.read_text())
    settings['obfs'] = {'type': 'salamander', 'salamander': {'password': 'test-only'}}
    server.write_text(json.dumps(settings))
extra = '&obfs=salamander&obfs-password=test-only' if args.obfs else ''
link=f'hysteria2://test-only@127.0.0.1:{hyport}?sni=localhost{extra}#local'
reply=json.loads(subprocess.check_output([args.converter],input=json.dumps({'apiVersion':3,'method':'convertShareLinksToXrayJson','payload':{'text':link}}).encode()))
assert reply['success'],reply
out=reply['data']['outbounds'][0]; out['tag']='proxy'
out['streamSettings']['tlsSettings']['certificates']=[{'certificateFile':str(ROOT/'cert.pem'),'usage':'verify'}]
config=write('client.json',{'log':{'loglevel':'warning'},'inbounds':[{'listen':'127.0.0.1','port':socks,'protocol':'socks','settings':{'auth':'noauth','udp':True}}],'outbounds':[out]})
processes=[]
def start(args,name):
    with open(ROOT/name,'wb') as log:
        p=subprocess.Popen(args,stdout=log,stderr=log)
    processes.append(p); return p
def read_exact(stream, size):
    data = bytearray()
    while len(data) < size:
        chunk = stream.recv(size - len(data))
        if not chunk:
            raise EOFError("SOCKS ответ оборвался")
        data.extend(chunk)
    return bytes(data)

def fetch():
    return subprocess.run(['curl','-sS','--max-time','5','--noproxy','','--socks5-hostname',f'127.0.0.1:{socks}',f'http://127.0.0.1:{http.server_port}/'],capture_output=True)
try:
    hy=start([args.hysteria,'server','-c',str(server)],'server.log')
    xr=start([args.xray,'run','-c',str(config)],'client.log')
    time.sleep(.7)
    with socket.socket() as s:
        assert s.connect_ex(('127.0.0.1',hyport))!=0, 'unexpected TCP listener'
    for n in range(3):
        response=fetch(); assert response.returncode==0 and response.stdout==body,(response.returncode,response.stderr.decode(),len(response.stdout))
    print('TCP payload through UDP-only endpoint: 3/3,',len(body),'bytes each',flush=True)
    # SOCKS UDP ASSOCIATE и обмен настоящей датаграммой через туннель.
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as echo:
        echo.bind(('127.0.0.1',0)); echo.settimeout(5)
        def echo_one():
            data,addr=echo.recvfrom(4096); echo.sendto(data,addr)
        threading.Thread(target=echo_one,daemon=True).start()
        with socket.create_connection(('127.0.0.1',socks),timeout=5) as control:
            control.sendall(b'\x05\x01\x00'); assert read_exact(control, 2)==b'\x05\x00'
            control.sendall(b'\x05\x03\x00\x01'+b'\x00'*6)
            response=read_exact(control, 10); assert response[:2]==b'\x05\x00',response
            relay=(socket.inet_ntoa(response[4:8]),int.from_bytes(response[8:10],'big'))
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as udp:
                udp.settimeout(5)
                packet=b'\x00\x00\x00\x01'+socket.inet_aton('127.0.0.1')+echo.getsockname()[1].to_bytes(2,'big')+b'udp-test'
                udp.sendto(packet,relay); data,_=udp.recvfrom(4096); assert data[10:]==b'udp-test',data
    print('UDP payload through tunnel: passed',flush=True)
    # Отказ аутентификации проверяется без возможности прямого выхода.
    xr.terminate(); xr.wait(timeout=5)
    bad=json.loads(config.read_text())
    bad['outbounds'][0]['streamSettings']['hysteriaSettings']['auth']='wrong-test-only'
    bad_path=write('bad-auth.json',bad)
    xr=start([args.xray,'run','-c',str(bad_path)],'bad-auth.log')
    time.sleep(.3)
    response=fetch(); assert response.returncode!=0 and not response.stdout
    print('Wrong password: rejected',flush=True)
    xr.terminate(); xr.wait(timeout=5)
    xr=start([args.xray,'run','-c',str(config)],'restart-client.log')
    time.sleep(.3)
    response=fetch(); assert response.returncode==0 and response.stdout==body
    print('Client session restart: transfer restored',flush=True)
    if args.recovery:
        hy.terminate(); hy.wait(timeout=5)
        response=fetch(); assert response.returncode!=0 and not response.stdout
        print('Server stopped: transfer fails',flush=True)
        hy=start([args.hysteria,'server','-c',str(server)],'server-restart.log')
        time.sleep(.5)
        started=time.monotonic()
        for attempt in range(8):
            response=fetch()
            if response.returncode==0 and response.stdout==body: break
        else: raise AssertionError(('not recovered',response.stderr.decode()))
        print('Server restarted: transfer recovered after',round(time.monotonic()-started,1),'seconds',flush=True)
finally:
    for p in reversed(processes):
        if p.poll() is None:
            p.terminate()
            try:p.wait(timeout=5)
            except subprocess.TimeoutExpired:p.kill();p.wait()
    http.shutdown();http.server_close()
    workspace.cleanup()
