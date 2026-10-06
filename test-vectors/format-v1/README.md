# Vault Format 1 conformance fixtures

These fixtures use synthetic IDs, keys, and passwords. Every secret-looking
value here is public test data and must never be reused in a real vault.

`vault.json`, `users.enc`, `manifests/66666666-7777-4666-8666-666666666666.enc`,
and `storage/blobs/bb/bbbbbbbb-cccc-4ddd-8eee-ffffffffffff.edv` are a small,
readable vault fixture. `vectors.json` gives the exact plaintext bytes, keys,
nonces, AAD, and expected ciphertext/tag bytes used to create those files. The
Argon2id reference case, all six AAD values, three wrapped keys, encrypted
registry and manifest, blob bytes, tampering behavior, and logical-name
examples are consumed by `FormatV1VectorTest`.

The fixed expected values were generated independently of the Java production
helpers with Python 3.13.5, `argon2-cffi` 25.1.0, and `cryptography` 46.0.7.
The Java suite then checks those values with the production key deriver and
repository readers, direct JCE AES-GCM, and the Bouncy Castle streaming blob
implementation. The Argon2id parameters and version are specified by
[RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html); AES-GCM is specified
by [NIST SP 800-38D](https://csrc.nist.gov/pubs/sp/800/38/d/final).

To regenerate the deterministic files from these fixed inputs, install the
pinned packages in `requirements.txt` and run:

```powershell
python generate_vectors.py
```

Regeneration overwrites only files under this directory. It does not create a
vault or use production credentials.
