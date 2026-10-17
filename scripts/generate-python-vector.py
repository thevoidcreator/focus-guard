#!/usr/bin/env python3
"""Public golden vector only: private key exists only in this process."""
import base64, hashlib, json, pathlib
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

def b64(b): return base64.urlsafe_b64encode(b).rstrip(b'=').decode()
root = pathlib.Path(__file__).resolve().parents[1]
key = ec.generate_private_key(ec.SECP256R1())
spki = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
keyid = b64(hashlib.sha256(spki).digest())
payload = dict(protocolVersion=1, messageType='removal_start', installationId='vector-phone',
               requestId='vector-request', nonce=b64(bytes(range(32))), keyId=keyid,
               policyVersion=1, removalFlowId='vector-flow', phase='request_start', requestDigest='',
               role='main', phoneKeyId=keyid, publicKey='', proposal=None)
dump = lambda o: json.dumps(o, separators=(',', ':'), ensure_ascii=False).encode()
signing = b64(dump(dict(alg='ES256', typ='FG1'))) + '.' + b64(dump(payload))
r,s = utils.decode_dss_signature(key.sign(signing.encode(), ec.ECDSA(hashes.SHA256())))
compact = signing + '.' + b64(r.to_bytes(32,'big') + s.to_bytes(32,'big'))
out = dict(publicSpki=b64(spki), compact=compact, requestDigest=b64(hashlib.sha256(compact.encode()).digest()))
(root/'protocol/vectors').mkdir(parents=True, exist_ok=True)
(root/'protocol/vectors/python.json').write_text(json.dumps(out, indent=2)+'\n')
