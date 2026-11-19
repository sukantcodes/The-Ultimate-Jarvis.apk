// Runs under Node 22+ (`node --experimental-strip-types --test sigcrypto.test.ts`) and Deno (`deno test`).
import { test } from "node:test";
import assert from "node:assert/strict";
import { createSign, generateKeyPairSync } from "node:crypto";
import {
  b64encode, canonicalRequest, derToRaw, fingerprint, generatePairingCode, normalizeCode, sha256Hex, verifySignature,
} from "./sigcrypto.ts";
import { CAPABILITIES, isKnownCapability, needsSourceConfirmation } from "./catalog.ts";

function keypair() {
  const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
  const spki = b64encode(new Uint8Array(publicKey.export({ type: "spki", format: "der" })));
  return { spki, privateKey };
}
const signDer = (privateKey: any, msg: string) =>
  b64encode(new Uint8Array(createSign("SHA256").update(msg).sign({ key: privateKey, dsaEncoding: "der" })));

test("DER signatures from a Java-style signer verify", async () => {
  const { spki, privateKey } = keypair();
  for (let n = 0; n < 40; n++) { // exercises short r/s (leading-zero stripping) and high-bit padding
    const msg = canonicalRequest("heartbeat", "dev", String(1000 + n), "nonce" + n, await sha256Hex("{}" + n));
    assert.equal(await verifySignature(spki, msg, signDer(privateKey, msg)), true);
  }
});

test("a tampered message or other key is rejected", async () => {
  const a = keypair(), b = keypair();
  const msg = canonicalRequest("send_command", "dev", "1", "n".repeat(16), await sha256Hex("body"));
  const sig = signDer(a.privateKey, msg);
  assert.equal(await verifySignature(a.spki, msg + "x", sig), false);
  assert.equal(await verifySignature(b.spki, msg, sig), false);
  assert.equal(await verifySignature(a.spki, msg, "AAAA"), false);
  assert.equal(await verifySignature("not-a-key", msg, sig), false);
});

test("derToRaw rejects malformed input", () => {
  assert.equal(derToRaw(new Uint8Array([0x31, 0x00])), null);
  assert.equal(derToRaw(new Uint8Array([0x30, 0x02, 0x02, 0x01])), null);
});

test("fingerprint is 64 hex chars and stable", async () => {
  const { spki } = keypair();
  assert.match(await fingerprint(spki), /^[a-f0-9]{64}$/);
  assert.equal(await fingerprint(spki), await fingerprint(spki));
});

test("pairing codes are 8 unambiguous characters", () => {
  for (let i = 0; i < 200; i++) assert.match(generatePairingCode(), /^[A-HJKMNP-Z2-9]{8}$/);
  assert.equal(normalizeCode("ab-cd 23"), "ABCD23");
});

test("catalog: sensitive capabilities require confirmation and target consent", () => {
  assert.equal(isKnownCapability("device.battery"), true);
  assert.equal(isKnownCapability("constructor"), false);
  assert.equal(needsSourceConfirmation("device.battery"), false);
  assert.equal(needsSourceConfirmation("device.lock"), true);
  for (const c of ["location.get", "camera.open"]) {
    assert.equal(CAPABILITIES[c].sensitivity, "high");
    assert.equal(CAPABILITIES[c].targetConsent, true);
  }
});
