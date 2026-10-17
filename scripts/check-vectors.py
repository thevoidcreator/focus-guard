#!/usr/bin/env python3
import base64, hashlib, json, pathlib
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

root=pathlib.Path(__file__).resolve().parents[1]
dec=lambda s: base64.urlsafe_b64decode(s+'='*((-len(s))%4))
for path in sorted((root/'protocol/vectors').glob('*.json')):
    v=json.loads(path.read_text()); a,b,c=v['compact'].split('.')
    assert json.loads(dec(a)) == {'alg':'ES256','typ':'FG1'}
    signature=dec(c); assert len(signature)==64
    key=serialization.load_der_public_key(dec(v['publicSpki']))
    key.verify(utils.encode_dss_signature(int.from_bytes(signature[:32],'big'),int.from_bytes(signature[32:],'big')),
               (a+'.'+b).encode(), ec.ECDSA(hashes.SHA256()))
    assert dec(v['requestDigest']) == hashlib.sha256(v['compact'].encode()).digest()
    print('PASS exact-byte ES256 R||S:', path.name)
