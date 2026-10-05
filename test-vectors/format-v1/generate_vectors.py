#!/usr/bin/env python3
"""Regenerate the public, deterministic Vault Format 1 conformance fixtures.

Requires argon2-cffi 25.1.0 and cryptography 46.0.7. Inputs are synthetic and
public; no production vault data or credentials belong in this file.
"""

from __future__ import annotations

import base64
import json
from pathlib import Path

from argon2.low_level import Type, hash_secret_raw
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


ROOT = Path(__file__).resolve().parent
ALGORITHM = "AES/GCM/NoPadding"
CREATED_AT = "2026-10-05T12:00:00Z"
VAULT_PASSWORD = "Vector vault passphrase"
ACCOUNT_PASSWORD = "contraseña-ñ€"

IDS = {
    "vaultId": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
    "userId": "11111111-2222-4333-8444-555555555555",
    "manifestId": "66666666-7777-4666-8666-666666666666",
    "rootFolderId": "00000000-0000-4000-8000-000000000001",
    "folderId": "00000000-0000-4000-8000-000000000002",
    "fileId": "00000000-0000-4000-8000-000000000003",
    "blobId": "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff",
    "pendingBlobId": "cccccccc-dddd-4eee-8fff-000000000001",
}


def hex_bytes(value: bytes) -> str:
    return value.hex()


def b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def derive(password: bytes, salt: bytes, memory_kib: int, iterations: int, parallelism: int) -> bytes:
    return hash_secret_raw(
        secret=password,
        salt=salt,
        time_cost=iterations,
        memory_cost=memory_kib,
        parallelism=parallelism,
        hash_len=32,
        type=Type.ID,
        version=19,
    )


def argon_vector(name: str, password: bytes, salt: bytes, memory_kib: int,
                 iterations: int, parallelism: int, password_text: str | None = None) -> dict:
    result = {
        "name": name,
        "passwordUtf8Hex": hex_bytes(password),
        "saltHex": hex_bytes(salt),
        "memoryKiB": memory_kib,
        "iterations": iterations,
        "parallelism": parallelism,
        "outputHex": hex_bytes(derive(password, salt, memory_kib, iterations, parallelism)),
    }
    if password_text is not None:
        result["passwordUtf8"] = password_text
    return result


def encrypt(key: bytes, nonce: bytes, aad: bytes, plaintext: bytes) -> bytes:
    return AESGCM(key).encrypt(nonce, plaintext, aad)


def envelope(nonce: bytes, ciphertext_and_tag: bytes) -> dict:
    return {
        "version": 1,
        "algorithm": ALGORITHM,
        "nonce": b64(nonce),
        "ciphertext": b64(ciphertext_and_tag),
    }


def write_json(path: Path, value: object, *, pretty: bool = False) -> None:
    separators = (",", ": ") if pretty else (",", ":")
    text = json.dumps(value, ensure_ascii=False, indent=2 if pretty else None,
                      separators=separators) + "\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))


def main() -> None:
    vault_salt = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
    account_salt = bytes.fromhex("f0e0d0c0b0a090807060504030201000")
    reference_salt = bytes.fromhex("02" * 16)

    reference = argon_vector(
        "Independent Argon2id v1.3 vector (Python argon2-cffi)",
        bytes.fromhex("01" * 32), reference_salt, 32, 3, 4,
    )
    vault_password_vector = argon_vector(
        "EncryptDrive vault-password profile",
        VAULT_PASSWORD.encode("utf-8"), vault_salt, 65_536, 3, 1, VAULT_PASSWORD,
    )
    account_password_vector = argon_vector(
        "EncryptDrive account-password profile",
        ACCOUNT_PASSWORD.encode("utf-8"), account_salt, 65_536, 3, 1, ACCOUNT_PASSWORD,
    )

    aad_text = {
        "vaultKey": f"EncryptDrive|vault-key|v1|{IDS['vaultId']}",
        "users": f"EncryptDrive|users|v1|{IDS['vaultId']}",
        "userKey": f"EncryptDrive|user-key|v1|{IDS['vaultId']}|{IDS['userId']}",
        "manifest": f"EncryptDrive|manifest|v1|{IDS['vaultId']}|{IDS['userId']}",
        "fileKey": f"EncryptDrive|file-key|v1|{IDS['vaultId']}|{IDS['userId']}|{IDS['fileId']}",
        "fileContent": f"EncryptDrive|file|v1|{IDS['vaultId']}|{IDS['userId']}|{IDS['fileId']}",
    }
    aad_vectors = {
        name: {"text": text, "utf8Hex": hex_bytes(text.encode("utf-8"))}
        for name, text in aad_text.items()
    }

    vkek = bytes.fromhex(vault_password_vector["outputHex"])
    account_key = bytes.fromhex(account_password_vector["outputHex"])
    registry_key = bytes(range(0x20, 0x40))
    user_master_key = bytes(range(0x40, 0x60))
    file_key = bytes(range(0x60, 0x80))

    def wrap_vector(key: bytes, plaintext: bytes, nonce_hex: str, aad_name: str) -> dict:
        nonce = bytes.fromhex(nonce_hex)
        aad = aad_text[aad_name].encode("utf-8")
        ciphertext_and_tag = encrypt(key, nonce, aad, plaintext)
        return {
            "keyHex": hex_bytes(key),
            "plaintextHex": hex_bytes(plaintext),
            "nonceHex": hex_bytes(nonce),
            "aadUtf8": aad.decode("utf-8"),
            "ciphertextAndTagHex": hex_bytes(ciphertext_and_tag),
        }

    wrapped_registry = wrap_vector(vkek, registry_key, "101112131415161718191a1b", "vaultKey")
    wrapped_user = wrap_vector(account_key, user_master_key, "303132333435363738393a3b", "userKey")
    wrapped_file = wrap_vector(user_master_key, file_key, "505152535455565758595a5b", "fileKey")

    user_kdf = {
        "algorithm": "Argon2id",
        "memoryKiB": 65_536,
        "iterations": 3,
        "parallelism": 1,
        "salt": b64(account_salt),
    }
    registry_plaintext = {
        "formatVersion": 1,
        "users": [{
            "userId": IDS["userId"],
            "fullName": "Vector Example",
            "username": "VectorUser",
            "normalizedUsername": "vectoruser",
            "createdAt": CREATED_AT,
            "manifestId": IDS["manifestId"],
            "userKdf": user_kdf,
            "wrappedUserMasterKey": envelope(
                bytes.fromhex(wrapped_user["nonceHex"]),
                bytes.fromhex(wrapped_user["ciphertextAndTagHex"]),
            ),
        }],
    }
    registry_plaintext_bytes = json.dumps(
        registry_plaintext, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    users_nonce = bytes.fromhex("202122232425262728292a2b")
    users_aad = aad_text["users"].encode("utf-8")
    users_ciphertext = encrypt(registry_key, users_nonce, users_aad, registry_plaintext_bytes)
    user_registry_vector = {
        "keyHex": hex_bytes(registry_key),
        "nonceHex": hex_bytes(users_nonce),
        "aadUtf8": users_aad.decode("utf-8"),
        "plaintextUtf8": registry_plaintext_bytes.decode("utf-8"),
        "ciphertextAndTagHex": hex_bytes(users_ciphertext),
    }

    blob_plaintext = b"EncryptDrive Format 1 vector file.\n\x00\xff"
    blob_nonce = bytes.fromhex("a0a1a2a3a4a5a6a7a8a9aaab")
    file_aad = aad_text["fileContent"].encode("utf-8")
    blob_ciphertext = encrypt(file_key, blob_nonce, file_aad, blob_plaintext)
    file_blob_vector = {
        "keyHex": hex_bytes(file_key),
        "nonceHex": hex_bytes(blob_nonce),
        "aadUtf8": file_aad.decode("utf-8"),
        "plaintextHex": hex_bytes(blob_plaintext),
        "ciphertextAndTagHex": hex_bytes(blob_ciphertext),
    }

    manifest_plaintext = {
        "formatVersion": 1,
        "userId": IDS["userId"],
        "rootFolderId": IDS["rootFolderId"],
        "entries": [
            {
                "entryId": IDS["rootFolderId"],
                "kind": "FOLDER",
                "name": "/",
                "createdAt": CREATED_AT,
                "modifiedAt": CREATED_AT,
            },
            {
                "entryId": IDS["folderId"],
                "kind": "FOLDER",
                "parentId": IDS["rootFolderId"],
                "name": "docs",
                "createdAt": CREATED_AT,
                "modifiedAt": CREATED_AT,
            },
            {
                "entryId": IDS["fileId"],
                "kind": "FILE",
                "parentId": IDS["folderId"],
                "name": "notes.txt",
                "createdAt": CREATED_AT,
                "modifiedAt": CREATED_AT,
                "plainSize": len(blob_plaintext),
                "blobId": IDS["blobId"],
                "wrappedFileKey": envelope(
                    bytes.fromhex(wrapped_file["nonceHex"]),
                    bytes.fromhex(wrapped_file["ciphertextAndTagHex"]),
                ),
                "contentNonce": b64(blob_nonce),
            },
        ],
        "pendingDeletions": [{"blobId": IDS["pendingBlobId"], "queuedAt": CREATED_AT}],
    }
    manifest_plaintext_bytes = json.dumps(
        manifest_plaintext, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    manifest_nonce = bytes.fromhex("404142434445464748494a4b")
    manifest_aad = aad_text["manifest"].encode("utf-8")
    manifest_ciphertext = encrypt(user_master_key, manifest_nonce, manifest_aad, manifest_plaintext_bytes)
    manifest_vector = {
        "keyHex": hex_bytes(user_master_key),
        "nonceHex": hex_bytes(manifest_nonce),
        "aadUtf8": manifest_aad.decode("utf-8"),
        "plaintextUtf8": manifest_plaintext_bytes.decode("utf-8"),
        "ciphertextAndTagHex": hex_bytes(manifest_ciphertext),
    }

    header = {
        "formatVersion": 1,
        "vaultId": IDS["vaultId"],
        "createdAt": CREATED_AT,
        "kdf": {
            "algorithm": "Argon2id",
            "memoryKiB": 65_536,
            "iterations": 3,
            "parallelism": 1,
            "salt": b64(vault_salt),
        },
        "wrappedRegistryKey": envelope(
            bytes.fromhex(wrapped_registry["nonceHex"]),
            bytes.fromhex(wrapped_registry["ciphertextAndTagHex"]),
        ),
    }

    vectors = {
        "ids": IDS,
        "logicalNames": {
            "accepted": [
                {"input": "  Quarterly report  ", "stored": "Quarterly report"},
                {"input": "café", "stored": "café"},
                {"input": "cafe\u0301", "stored": "cafe\u0301"},
                {"input": "資料", "stored": "資料"},
                {"input": "folder/name", "stored": "folder/name"},
                {"input": "CON", "stored": "CON"},
                {"input": "trailing.", "stored": "trailing."},
                {"input": "x" * 255, "stored": "x" * 255},
            ],
            "rejected": [
                {"input": "", "reason": "INVALID_NAME"},
                {"input": "   ", "reason": "INVALID_NAME"},
                {"input": ".", "reason": "INVALID_NAME"},
                {"input": "..", "reason": "INVALID_NAME"},
                {"input": "bad\x00name", "reason": "INVALID_NAME"},
                {"input": "x" * 256, "reason": "INVALID_NAME"},
            ],
            "caseCollision": {
                "existing": "Report",
                "input": "report",
                "reason": "DUPLICATE_NAME",
            },
            "distinctUnicodeForms": {
                "composed": "café",
                "decomposed": "cafe\u0301",
            },
        },
        "argon2id": {
            "reference": reference,
            "vaultPassword": vault_password_vector,
            "accountPassword": account_password_vector,
        },
        "aad": aad_vectors,
        "keyWraps": {
            "wrappedRegistryKey": wrapped_registry,
            "wrappedUserMasterKey": wrapped_user,
            "wrappedFileKey": wrapped_file,
        },
        "userRegistry": user_registry_vector,
        "manifest": manifest_vector,
        "fileBlob": file_blob_vector,
    }

    blob_id = IDS["blobId"]
    shard = blob_id.replace("-", "")[:2]
    write_json(ROOT / "vault.json", header, pretty=True)
    write_json(ROOT / "users.enc", envelope(users_nonce, users_ciphertext))
    write_json(ROOT / "manifests" / f"{IDS['manifestId']}.enc",
               envelope(manifest_nonce, manifest_ciphertext))
    blob_path = ROOT / "storage" / "blobs" / shard / f"{blob_id}.edv"
    blob_path.parent.mkdir(parents=True, exist_ok=True)
    blob_path.write_bytes(blob_ciphertext)
    write_json(ROOT / "vectors.json", vectors, pretty=True)


if __name__ == "__main__":
    main()
