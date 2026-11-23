import test from "node:test";
import assert from "node:assert/strict";
import {
  branchFromRef, branchSlug, buildApkUrl, expectedTag, GITHUB_ISSUER, isAdminActor, isWorkflowFile,
  type OidcClaims, OtaError, validatePublishBody, verifyDerivedBranch, verifyGithubOidc, verifyReleaseAsset,
  verifyTagCommit,
} from "./logic.ts";

const REPO = "sukantcodes/The-Ultimate-Jarvis.apk";
const AUD = "https://example.supabase.co/functions/v1/ota-publish";
const SHA = "a".repeat(40);
const HASH = "ab".repeat(32).toUpperCase();
const NOW = 1_800_000_000;

const b64url = (data: Uint8Array | string) =>
  Buffer.from(typeof data === "string" ? data : data).toString("base64url");

async function makeKey() {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true, ["sign", "verify"]);
  const jwk = await crypto.subtle.exportKey("jwk", pair.publicKey);
  return { pair, jwks: { keys: [{ ...jwk, kid: "k1", kty: "RSA" }] } };
}

function claims(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    iss: GITHUB_ISSUER, aud: AUD, sub: "repo:" + REPO + ":ref:refs/heads/Antigravity", exp: NOW + 300, nbf: NOW - 10,
    repository: REPO, repository_owner: "sukantcodes", ref: "refs/heads/Antigravity", sha: SHA, run_id: "777",
    event_name: "push", actor: "sukantcodes",
    job_workflow_ref: REPO + "/.github/workflows/build.yml@refs/heads/Antigravity", ...over,
  };
}

async function sign(pair: CryptoKeyPair, payload: Record<string, unknown>, header: Record<string, unknown> = {}) {
  const h = b64url(JSON.stringify({ alg: "RS256", typ: "JWT", kid: "k1", ...header }));
  const p = b64url(JSON.stringify(payload));
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", pair.privateKey, new TextEncoder().encode(h + "." + p));
  return h + "." + p + "." + b64url(new Uint8Array(sig));
}

async function rejects(promise: Promise<unknown>, code: string) {
  await assert.rejects(promise, (e: unknown) => e instanceof OtaError && e.code === code, "expected " + code);
}

test("accepts a valid GitHub OIDC token", async () => {
  const { pair, jwks } = await makeKey();
  const out = await verifyGithubOidc(await sign(pair, claims()), { jwks, audience: AUD, repository: REPO, nowSeconds: NOW });
  assert.equal(out.ref, "refs/heads/Antigravity");
});

test("repository comparison is case-insensitive", async () => {
  const { pair, jwks } = await makeKey();
  const token = await sign(pair, claims({ repository: REPO.toLowerCase() }));
  await verifyGithubOidc(token, { jwks, audience: AUD, repository: REPO, nowSeconds: NOW });
});

test("rejects a token signed by a different key", async () => {
  const a = await makeKey();
  const b = await makeKey();
  await rejects(verifyGithubOidc(await sign(b.pair, claims()), { jwks: a.jwks, audience: AUD, repository: REPO, nowSeconds: NOW }), "oidc_bad_signature");
});

test("rejects tampered payloads", async () => {
  const { pair, jwks } = await makeKey();
  const token = await sign(pair, claims());
  const [h, , s] = token.split(".");
  const forged = h + "." + b64url(JSON.stringify(claims({ ref: "refs/heads/main" }))) + "." + s;
  await rejects(verifyGithubOidc(forged, { jwks, audience: AUD, repository: REPO, nowSeconds: NOW }), "oidc_bad_signature");
});

test("rejects wrong issuer, audience, expiry, not-before, repository and algorithm", async () => {
  const { pair, jwks } = await makeKey();
  const o = { jwks, audience: AUD, repository: REPO, nowSeconds: NOW };
  await rejects(verifyGithubOidc(await sign(pair, claims({ iss: "https://evil.example" })), o), "oidc_bad_issuer");
  await rejects(verifyGithubOidc(await sign(pair, claims({ aud: "https://other" })), o), "oidc_bad_audience");
  await rejects(verifyGithubOidc(await sign(pair, claims({ exp: NOW - 500 })), o), "oidc_expired");
  await rejects(verifyGithubOidc(await sign(pair, claims({ nbf: NOW + 500 })), o), "oidc_not_yet_valid");
  await rejects(verifyGithubOidc(await sign(pair, claims({ repository: "attacker/fork" })), o), "oidc_wrong_repository");
  await rejects(verifyGithubOidc(await sign(pair, claims(), { alg: "HS256" }), o), "oidc_bad_header");
  await rejects(verifyGithubOidc(await sign(pair, claims(), { kid: "unknown" }), o), "oidc_unknown_key");
  await rejects(verifyGithubOidc("not-a-jwt", o), "oidc_malformed");
  await rejects(verifyGithubOidc(await sign(pair, claims({ run_id: undefined })), o), "oidc_missing_claim");
});

test("branch refs", () => {
  assert.equal(branchFromRef("refs/heads/Antigravity"), "Antigravity");
  assert.equal(branchFromRef("refs/heads/feature/ota-x"), "feature/ota-x");
  assert.throws(() => branchFromRef("refs/tags/v1"), OtaError);
  assert.throws(() => branchFromRef("refs/pull/1/merge"), OtaError);
  assert.throws(() => branchFromRef("refs/heads/bad branch"), OtaError);
});

test("tags are derived from branch and version code", () => {
  assert.equal(branchSlug("Antigravity"), "antigravity");
  assert.equal(branchSlug("feature/Neon_UI"), "feature-neon-ui");
  assert.equal(expectedTag("Antigravity", 11800), "ota-antigravity-11800");
  assert.equal(buildApkUrl(REPO, "ota-antigravity-11800", "jarvis.apk"),
    "https://github.com/" + REPO + "/releases/download/ota-antigravity-11800/jarvis.apk");
});

test("workflow and admin gating", () => {
  const c = claims() as unknown as OidcClaims;
  assert.ok(isWorkflowFile(c, REPO, "build.yml"));
  assert.ok(!isWorkflowFile(c, REPO, "ota-branch-admin.yml"));
  assert.ok(!isWorkflowFile({ ...c, job_workflow_ref: "attacker/fork/.github/workflows/build.yml@refs/heads/Antigravity" }, REPO, "build.yml"));
  assert.ok(!isWorkflowFile({ ...c, job_workflow_ref: REPO + "/.github/workflows/build.yml@refs/heads/other" }, REPO, "build.yml"));
  assert.ok(isAdminActor(c, undefined));
  assert.ok(!isAdminActor({ ...c, actor: "someone-else" }, undefined));
  assert.ok(isAdminActor({ ...c, actor: "Friend" }, "friend, sukantcodes"));
});

function body(over: Record<string, unknown> = {}) {
  return {
    version_code: 11800, version_name: "1.8.7-b1800", tag: "ota-antigravity-11800", asset_name: "jarvis-antigravity-11800.apk",
    size_bytes: 150_000_000, sha256: HASH.toLowerCase(), cert_sha256: HASH, min_sdk: 26, min_supported_version_code: 1,
    release_notes: "notes", commit_sha: SHA, run_id: 777, ...over,
  };
}

test("validates a publish request against the verified claims", () => {
  const c = claims() as unknown as OidcClaims;
  const out = validatePublishBody(body(), c, "Antigravity");
  assert.equal(out.sha256, HASH);
  assert.equal(out.versionCode, 11800);
  const bad = (over: Record<string, unknown>, code: string) =>
    assert.throws(() => validatePublishBody(body(over), c, "Antigravity"), (e: unknown) => e instanceof OtaError && e.code === code);
  bad({ tag: "ota-other-11800" }, "bad_tag");
  bad({ commit_sha: "b".repeat(40) }, "commit_mismatch");
  bad({ run_id: 778 }, "run_mismatch");
  bad({ sha256: "xyz" }, "bad_sha256");
  bad({ asset_name: "../evil.apk" }, "bad_asset_name");
  bad({ asset_name: "evil.exe" }, "bad_asset_name");
  bad({ size_bytes: 500_000_000 }, "bad_size");
  bad({ version_code: 0 }, "bad_version_code");
  bad({ version_name: "x".repeat(41) }, "bad_version_name");
  bad({ min_supported_version_code: 99999 }, "bad_min_supported");
  bad({ release_notes: "x".repeat(8001) }, "notes_too_long");
});

test("verifies the GitHub release asset: size, digest, upload state", () => {
  const input = validatePublishBody(body(), claims() as unknown as OidcClaims, "Antigravity");
  const good = () => ({
    tag_name: "ota-antigravity-11800", draft: false,
    assets: [{ name: "jarvis-antigravity-11800.apk", state: "uploaded", size: 150_000_000, digest: "sha256:" + HASH.toLowerCase() }],
  });
  verifyReleaseAsset(good(), input);
  const fails = (mutate: (r: any) => void, code: string) => {
    const r = good(); mutate(r);
    assert.throws(() => verifyReleaseAsset(r, input), (e: unknown) => e instanceof OtaError && e.code === code);
  };
  fails((r) => { r.draft = true; }, "release_is_draft");
  fails((r) => { r.tag_name = "other"; }, "release_tag_mismatch");
  fails((r) => { r.assets = []; }, "asset_not_found");
  fails((r) => { r.assets[0].state = "starter"; }, "asset_not_uploaded");
  fails((r) => { r.assets[0].size = 1; }, "asset_size_mismatch");
  fails((r) => { delete r.assets[0].digest; }, "asset_digest_unavailable");
  fails((r) => { r.assets[0].digest = "sha256:" + "0".repeat(64); }, "asset_digest_mismatch");
});

test("tag must point at the built commit", () => {
  verifyTagCommit({ object: { type: "commit", sha: SHA } }, SHA);
  assert.throws(() => verifyTagCommit({ object: { type: "commit", sha: "c".repeat(40) } }, SHA), OtaError);
  assert.throws(() => verifyTagCommit({ object: { type: "tag", sha: SHA } }, SHA), OtaError);
});

test("derived-branch check", () => {
  verifyDerivedBranch("feature-x", { status: "ahead", merge_base_commit: { sha: SHA } });
  assert.throws(() => verifyDerivedBranch("main", { status: "ahead", merge_base_commit: { sha: SHA } }), OtaError);
  assert.throws(() => verifyDerivedBranch("feature-x", { status: "ahead" }), OtaError);
});
