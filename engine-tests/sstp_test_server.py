#!/usr/bin/env python3
"""Minimal SSTP/PPP *test* server, written independently of the Java client, to exercise it.

python3 sstp_test_server.py PORT AUTH   (AUTH = pap | md5 | mschapv2)
Deliberately exercises: LCP reject (PFC/ACFC), LCP nak (EAP auth), IPCP reject (VJ),
IPCP nak (IP + DNS), unknown protocol (IPv6CP), SSTP echo, crypto binding verification.
Echoes IPv4 packets back with src/dst swapped. Prints CHECK lines for the test harness.
"""
import hashlib, hmac, os, socket, ssl, struct, sys, threading, time, datetime
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.ciphers import Cipher, modes
from cryptography.hazmat.decrepit.ciphers.algorithms import TripleDES

USER, PASSWORD = "vpn", "vpn"
LOG = []
def check(name, ok):
    print(f"CHECK {name}: {'PASS' if ok else 'FAIL'}", flush=True)

# ---------- independent MD4 ----------
def md4(msg: bytes) -> bytes:
    def rol(x, n): x &= 0xffffffff; return ((x << n) | (x >> (32 - n))) & 0xffffffff
    ml = len(msg) * 8
    msg += b"\x80" + b"\x00" * ((55 - len(msg)) % 64) + struct.pack("<Q", ml)
    h = [0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476]
    for i in range(0, len(msg), 64):
        X = list(struct.unpack("<16I", msg[i:i+64])); a, b, c, d = h
        F = lambda x, y, z: (x & y) | (~x & z)
        G = lambda x, y, z: (x & y) | (x & z) | (y & z)
        H = lambda x, y, z: x ^ y ^ z
        for k, s in zip(range(16), [3, 7, 11, 19] * 4):
            a, b, c, d = d, rol(a + F(b, c, d) + X[k], s), b, c
        for k, s in zip([0,4,8,12,1,5,9,13,2,6,10,14,3,7,11,15], [3, 5, 9, 13] * 4):
            a, b, c, d = d, rol(a + G(b, c, d) + X[k] + 0x5A827999, s), b, c
        for k, s in zip([0,8,4,12,2,10,6,14,1,9,5,13,3,11,7,15], [3, 9, 11, 15] * 4):
            a, b, c, d = d, rol(a + H(b, c, d) + X[k] + 0x6ED9EBA1, s), b, c
        h = [(x + y) & 0xffffffff for x, y in zip(h, [a, b, c, d])]
    return struct.pack("<4I", *h)

assert md4(b"abc").hex() == "a448017aaf21d8525fc10ae87aa6729d"

def des_key(k7):
    bits = int.from_bytes(k7, "big")
    return bytes((((bits >> (56 - 7 * (i + 1))) & 0x7f) << 1) for i in range(8))

def des(k7, data):
    e = Cipher(TripleDES(des_key(k7)), modes.ECB()).encryptor()
    return e.update(data) + e.finalize()

def nt_hash(pw): return md4(pw.encode("utf-16-le"))
def challenge_hash(pc, ac, user): return hashlib.sha1(pc + ac + user.encode()).digest()[:8]
def nt_response(ac, pc, user, pw):
    ch = challenge_hash(pc, ac, user); z = nt_hash(pw) + b"\0" * 5
    return b"".join(des(z[i:i+7], ch) for i in (0, 7, 14))
def auth_response(pw, ntr, pc, ac, user):
    d = hashlib.sha1(md4(nt_hash(pw)) + ntr + b"Magic server to client signing constant").digest()
    d = hashlib.sha1(d + challenge_hash(pc, ac, user) + b"Pad to make it do more than one iteration").digest()
    return "S=" + d.hex().upper()
M2 = b"On the client side, this is the send key; on the server side, it is the receive key."
M3 = b"On the client side, this is the receive key; on the server side, it is the send key."
def server_hlak(pw, ntr):
    mk = hashlib.sha1(md4(nt_hash(pw)) + ntr + b"This is the MPPE Master Key").digest()[:16]
    k = lambda magic: hashlib.sha1(mk + b"\0" * 40 + magic + b"\xf2" * 40).digest()[:16]
    return k(M3) + k(M2)   # server MasterSendKey || MasterReceiveKey

# ---------- RFC 2759 test vectors for the independent implementation ----------
_ac = bytes.fromhex("5B5D7C7D7B3F2F3E3C2C602132262628"); _pc = bytes.fromhex("21402324255E262A28295F2B3A337C7E")
_ntr = nt_response(_ac, _pc, "User", "clientPass")
assert _ntr.hex().upper() == "82309ECD8D708B5EA08FAA3981CD83544233114A3D85D6DF", _ntr.hex()
assert auth_response("clientPass", _ntr, _pc, _ac, "User") == "S=407A5589115FD0D6209F510FE9C04566932CDA56"

# ---------- TLS cert ----------
def make_cert(dirn):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test.opengw.net")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(1).not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=30)).sign(key, hashes.SHA256()))
    cp, kp = os.path.join(dirn, "c.pem"), os.path.join(dirn, "k.pem")
    open(cp, "wb").write(cert.public_bytes(serialization.Encoding.PEM))
    open(kp, "wb").write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                           serialization.NoEncryption()))
    return cp, kp, cert.public_bytes(serialization.Encoding.DER)

class Session:
    def __init__(self, conn, auth, cert_der):
        self.c, self.auth, self.der = conn, auth, cert_der
        self.lock = threading.Lock(); self.id = 100
        self.nonce = os.urandom(32)
        self.lcp_round = 0; self.ipcp_round = 0
        self.lcp_local = self.lcp_remote = False
        self.auth_ok = False; self.hlak = b"\0" * 32
        self.ipcp_ok = False; self.got_proto_rej = False; self.got_echo_resp = False
        self.echoed = 0; self.call_connected = False

    def nid(self): self.id = (self.id + 1) & 0xff; return self.id
    def send(self, b):
        with self.lock: self.c.sendall(b)
    def data(self, proto, payload, acfc=True):
        body = (b"\xff\x03" if acfc else b"") + struct.pack(">H", proto) + payload
        self.send(struct.pack(">BBH", 0x10, 0, 4 + len(body)) + body)
    def cp(self, proto, code, ident, d): self.data(proto, struct.pack(">BBH", code, ident, 4 + len(d)) + d)
    def control(self, t, attrs=b"", n=0):
        body = struct.pack(">HH", t, n) + attrs
        self.send(struct.pack(">BBH", 0x10, 1, 4 + len(body)) + body)
    def recv_exact(self, n):
        b = b""
        while len(b) < n:
            x = self.c.recv(n - len(b))
            if not x: raise EOFError
            b += x
        return b

    def lcp_req(self):
        opts = struct.pack(">BBH", 1, 4, 1500) + struct.pack(">BBI", 5, 6, 0x11223344)
        if self.lcp_round == 0: opts += b"\x07\x02\x08\x02"                 # PFC + ACFC -> expect reject
        if self.lcp_round <= 1: opts += b"\x03\x04\xc2\x27"                 # EAP -> expect nak
        else:
            opts += {"pap": b"\x03\x04\xc0\x23", "md5": b"\x03\x05\xc2\x23\x05",
                     "mschapv2": b"\x03\x05\xc2\x23\x81"}[self.auth]
        self.cp(0xC021, 1, self.nid(), opts)

    def run(self):
        head = b""
        while b"\r\n\r\n" not in head: head += self.c.recv(1)
        check("http request", head.startswith(b"SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/"))
        self.send(b"HTTP/1.1 200\r\nContent-Length: 18446744073709551615\r\nServer: Microsoft-HTTPAPI/2.0\r\n\r\n")
        while True:
            hdr = self.recv_exact(4)
            ln = struct.unpack(">H", hdr[2:4])[0] & 0x0fff
            pkt = hdr + self.recv_exact(ln - 4)
            if pkt[1] & 1: self.on_control(pkt)
            else: self.on_data(pkt)

    def on_control(self, p):
        t = struct.unpack(">H", p[4:6])[0]
        if t == 1:
            check("call connect request", p[8:14] == b"\x00\x01\x00\x06\x00\x01")
            attr = b"\x00\x04\x00\x28" + b"\x00\x00\x00\x03" + self.nonce
            self.control(2, attr, 1)
            self.lcp_req()
        elif t == 4:
            self.call_connected = True
            check("call connected length", len(p) == 112)
            check("nonce echoed", p[16:48] == self.nonce)
            check("sha256 chosen", p[15] == 2)
            check("cert hash", p[48:80] == hashlib.sha256(self.der).digest())
            cmk = hmac.new(self.hlak, b"SSTP inner method derived CMK" + b"\x20\x00\x01", hashlib.sha256).digest()
            zeroed = p[:80] + b"\0" * 32
            check("compound MAC", hmac.new(cmk, zeroed, hashlib.sha256).digest() == p[80:112])
        elif t == 9:
            self.got_echo_resp = True
            check("sstp echo response", True)
        elif t == 6:
            self.control(7)

    def on_data(self, p):
        off = 4
        if p[off:off+2] == b"\xff\x03": off += 2
        proto = struct.unpack(">H", p[off:off+2])[0]; off += 2
        d = p[off:]
        if proto == 0x0021: return self.on_ip(d)
        code, ident, ln = d[0], d[1], struct.unpack(">H", d[2:4])[0]
        body = d[4:ln]
        if proto == 0xC021:
            if code == 1:            # client conf-req
                check("client lcp has MRU+magic", body[:2] == b"\x01\x04" and b"\x05\x06" in body)
                self.cp(0xC021, 2, ident, body); self.lcp_remote = True
            elif code == 4:          # client rejects our options
                check("client rejects PFC+ACFC", body == b"\x07\x02\x08\x02")
                self.lcp_round = 1; self.lcp_req()
            elif code == 3:          # client naks EAP
                check("client naks EAP with MS-CHAPv2", body == b"\x03\x05\xc2\x23\x81")
                self.lcp_round = 2; self.lcp_req()
            elif code == 2:
                self.lcp_local = True
                if self.auth in ("md5", "mschapv2"):
                    self.chal = os.urandom(16); self.chap_id = self.nid()
                    self.cp(0xC223, 1, self.chap_id, bytes([16]) + self.chal + b"testsrv")
            elif code == 8:
                check("protocol reject for IPv6CP", body[:2] == b"\x80\x57"); self.got_proto_rej = True
            elif code == 10:
                check("lcp echo reply", True)
        elif proto == 0xC023 and code == 1:
            ul = body[0]; u = body[1:1+ul]; pl = body[1+ul]; pw = body[2+ul:2+ul+pl]
            ok = (u, pw) == (USER.encode(), PASSWORD.encode()); check("pap credentials", ok)
            self.cp(0xC023, 2 if ok else 3, ident, b"\x00"); self.auth_ok = ok
            self.after_auth()
        elif proto == 0xC223 and code == 2:
            vs = body[0]; val = body[1:1+vs]; name = body[1+vs:]
            check("chap id", ident == self.chap_id); check("chap name", name == USER.encode())
            if self.auth == "md5":
                ok = val == hashlib.md5(bytes([ident]) + PASSWORD.encode() + self.chal).digest()
                check("chap-md5 response", ok)
                self.cp(0xC223, 3 if ok else 4, ident, b"Welcome")
            else:
                pc, ntr = val[:16], val[24:48]
                ok = vs == 49 and ntr == nt_response(self.chal, pc, USER, PASSWORD)
                check("mschapv2 nt-response", ok)
                if ok:
                    self.hlak = server_hlak(PASSWORD, ntr)
                    self.cp(0xC223, 3, ident, (auth_response(PASSWORD, ntr, pc, self.chal, USER) + " M=OK").encode())
                else: self.cp(0xC223, 4, ident, b"E=691 R=0 V=3")
            self.auth_ok = ok
            self.after_auth()
        elif proto == 0x8021:
            if code == 1:
                opts = body; d = {}
                i = 0
                while i < len(opts): d[opts[i]] = opts[i+2:i+opts[i+1]]; i += opts[i+1]
                if d.get(3) == b"\0\0\0\0":
                    check("ipcp asks for IP and DNS", 129 in d and 131 in d)
                    nak = b"\x03\x06" + bytes([10, 0, 0, 5]) + b"\x81\x06" + bytes([8, 8, 8, 8]) + b"\x83\x06" + bytes([8, 8, 4, 4])
                    self.cp(0x8021, 3, ident, nak)
                else:
                    check("ipcp final request", d.get(3) == bytes([10, 0, 0, 5]) and d.get(129) == bytes([8, 8, 8, 8]))
                    self.cp(0x8021, 2, ident, body); self.ipcp_ok = True
            elif code == 4:
                check("client rejects VJ compression", body[:1] == b"\x02")
                self.ipcp_round = 1; self.ipcp_req()
            elif code == 2:
                check("client acks server IP", body == b"\x03\x06" + bytes([10, 0, 0, 1]))
                # Unknown protocol + echoes to test the client's handling.
                self.cp(0x8057, 1, self.nid(), b"\x01\x0a" + os.urandom(8))
                self.cp(0xC021, 9, self.nid(), struct.pack(">I", 0x11223344) + b"ping")
                self.control(8)

    def ipcp_req(self):
        opts = b"\x03\x06" + bytes([10, 0, 0, 1])
        if self.ipcp_round == 0: opts += b"\x02\x06\x00\x2d\x0f\x01"   # VJ compression -> expect reject
        self.cp(0x8021, 1, self.nid(), opts)

    def after_auth(self):
        if self.auth_ok: self.ipcp_req()

    def on_ip(self, d):
        if len(d) < 20: return
        check("ip from assigned address", d[12:16] == bytes([10, 0, 0, 5]))
        reply = d[:12] + d[16:20] + d[12:16] + d[20:]
        self.echoed += 1
        self.data(0x0021, reply)

def main():
    port, auth = int(sys.argv[1]), sys.argv[2]
    d = os.path.dirname(os.path.abspath(__file__))
    cp, kp, der = make_cert(d)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(cp, kp)
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port)); srv.listen(1)
    print("READY", flush=True)
    conn, _ = srv.accept()
    s = Session(ctx.wrap_socket(conn, server_side=True), auth, der)
    try: s.run()
    except (EOFError, ConnectionError, ssl.SSLError, OSError): pass
    check("call connected received", s.call_connected)
    check("protocol reject received", s.got_proto_rej)
    check("echo response received", s.got_echo_resp)
    check("ip packets echoed", s.echoed >= 3)
    print("DONE", flush=True)

if __name__ == "__main__":
    main()
