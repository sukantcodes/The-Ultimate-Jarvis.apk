// Per-device request signatures. The Android Keystore (and the Windows agent) sign with ECDSA P-256 / SHA-256.
// Java produces ASN.1 DER signatures; WebCrypto verifies raw r||s, so DER is converted here.
// Pure WebCrypto only, so this module runs unchanged in Deno (Edge Functions) and Node (tests).

const enc = new TextEncoder();

export function b64decode(s: string): Uint8Array {
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/"));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function b64encode(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

export function hex(bytes: ArrayBuffer | Uint8Array): string {
  return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function sha256Hex(data: string | Uint8Array): Promise<string> {
  const bytes = typeof data === "string" ? enc.encode(data) : data;
  return hex(await crypto.subtle.digest("SHA-256", bytes));
}

/** Converts an ASN.1 DER ECDSA signature to fixed-width r||s (64 bytes for P-256). Returns null if malformed. */
export function derToRaw(der: Uint8Array, size = 32): Uint8Array | null {
  let i = 0;
  if (der[i++] !== 0x30) return null;
  let len = der[i++];
  if (len & 0x80) {
    const n = len & 0x7f;
    if (n < 1 || n > 2) return null;
    len = 0;
    for (let k = 0; k < n; k++) len = (len << 8) | der[i++];
  }
  if (len !== der.length - i) return null;
  const out = new Uint8Array(size * 2);
  for (let part = 0; part < 2; part++) {
    if (der[i++] !== 0x02) return null;
    const total = der[i++];
    if (total === undefined || total < 1 || i + total > der.length) return null;
    let start = i;
    let l = total;
    while (l > 1 && der[start] === 0) { start++; l--; }
    if (l > size) return null;
    out.set(der.subarray(start, start + l), part * size + (size - l));
    i += total;
  }
  return i === der.length ? out : null;
}

/** Canonical string both sides sign: binds method, action, timestamp, nonce, device and the exact body bytes. */
export function canonicalRequest(
  action: string, deviceId: string, ts: string, nonce: string, bodySha256: string,
): string {
  return ["UDN1", "POST", action, deviceId, ts, nonce, bodySha256].join("\n");
}

export async function importPublicKey(spkiB64: string): Promise<CryptoKey | null> {
  try {
    return await crypto.subtle.importKey(
      "spki", b64decode(spkiB64), { name: "ECDSA", namedCurve: "P-256" }, true, ["verify"],
    );
  } catch {
    return null;
  }
}

export async function verifySignature(spkiB64: string, message: string, sigB64: string): Promise<boolean> {
  const key = await importPublicKey(spkiB64);
  if (!key) return false;
  let sig: Uint8Array;
  try { sig = b64decode(sigB64); } catch { return false; }
  const raw = sig.length === 64 ? sig : derToRaw(sig);
  if (!raw) return false;
  try {
    return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, raw, enc.encode(message));
  } catch {
    return false;
  }
}

/** SHA-256 of the SPKI bytes, used as the device's stable cryptographic fingerprint. */
export async function fingerprint(spkiB64: string): Promise<string> {
  return sha256Hex(b64decode(spkiB64));
}

/** Pairing codes: 8 characters from an unambiguous alphabet, ~40 bits. Stored only as a SHA-256 hash. */
export const PAIRING_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

export function generatePairingCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return [...bytes].map((b) => PAIRING_ALPHABET[b % PAIRING_ALPHABET.length]).join("");
}

export const normalizeCode = (s: string): string => s.toUpperCase().replace(/[^A-Z0-9]/g, "");
