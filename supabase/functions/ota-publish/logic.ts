// Pure, dependency-free logic for the ota-publish Edge Function. It uses only WebCrypto and plain objects so
// it runs unchanged on Deno (Supabase Edge) and on Node 22 (unit tests).

export const GITHUB_ISSUER = "https://token.actions.githubusercontent.com";
export const DEFAULT_REPOSITORY = "sukantcodes/The-Ultimate-Jarvis.apk";
export const DEFAULT_BRANCH = "Antigravity";
export const PUBLISH_WORKFLOW_FILE = "build.yml";
export const ADMIN_WORKFLOW_FILE = "ota-branch-admin.yml";
export const MAX_APK_BYTES = 400_000_000;

export class OtaError extends Error {
  code: string;
  status: number;
  constructor(code: string, status = 400) {
    super(code);
    this.code = code;
    this.status = status;
  }
}

export interface OidcClaims {
  iss: string;
  aud: string | string[];
  sub: string;
  exp: number;
  nbf?: number;
  repository: string;
  repository_owner: string;
  ref: string;
  sha: string;
  run_id: string;
  event_name: string;
  actor: string;
  job_workflow_ref: string;
  [key: string]: unknown;
}

export interface Jwks {
  keys: Array<Record<string, unknown>>;
}

const textDecoder = new TextDecoder();

export function b64urlToBytes(value: string): Uint8Array {
  const b64 = value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function parseJsonPart(part: string): Record<string, unknown> {
  try {
    const value = JSON.parse(textDecoder.decode(b64urlToBytes(part)));
    if (value && typeof value === "object" && !Array.isArray(value)) return value as Record<string, unknown>;
  } catch (_) { /* fall through */ }
  throw new OtaError("oidc_malformed", 401);
}

/** Verifies a GitHub Actions OIDC token: RS256 signature, issuer, audience, time window and repository. */
export async function verifyGithubOidc(
  token: string,
  opts: { jwks: Jwks; audience: string; repository: string; nowSeconds?: number },
): Promise<OidcClaims> {
  const parts = token.split(".");
  if (parts.length !== 3 || parts.some((p) => !/^[A-Za-z0-9_-]+$/.test(p))) throw new OtaError("oidc_malformed", 401);
  const header = parseJsonPart(parts[0]);
  const payload = parseJsonPart(parts[1]);
  if (header.alg !== "RS256" || typeof header.kid !== "string") throw new OtaError("oidc_bad_header", 401);
  const jwk = opts.jwks.keys.find((k) => k.kid === header.kid && k.kty === "RSA");
  if (!jwk) throw new OtaError("oidc_unknown_key", 401);

  const key = await crypto.subtle.importKey(
    "jwk",
    { kty: "RSA", n: jwk.n as string, e: jwk.e as string, alg: "RS256", ext: true },
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const signed = new TextEncoder().encode(parts[0] + "." + parts[1]);
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64urlToBytes(parts[2]), signed);
  if (!ok) throw new OtaError("oidc_bad_signature", 401);

  const now = opts.nowSeconds ?? Math.floor(Date.now() / 1000);
  const skew = 60;
  if (payload.iss !== GITHUB_ISSUER) throw new OtaError("oidc_bad_issuer", 401);
  const aud = payload.aud;
  const audOk = Array.isArray(aud) ? aud.includes(opts.audience) : aud === opts.audience;
  if (!audOk) throw new OtaError("oidc_bad_audience", 401);
  if (typeof payload.exp !== "number" || payload.exp + skew < now) throw new OtaError("oidc_expired", 401);
  if (typeof payload.nbf === "number" && payload.nbf - skew > now) throw new OtaError("oidc_not_yet_valid", 401);

  for (const field of ["repository", "repository_owner", "ref", "sha", "run_id", "event_name", "actor", "job_workflow_ref"]) {
    if (typeof payload[field] !== "string" || !(payload[field] as string)) throw new OtaError("oidc_missing_claim", 401);
  }
  if ((payload.repository as string).toLowerCase() !== opts.repository.toLowerCase()) {
    throw new OtaError("oidc_wrong_repository", 403);
  }
  return payload as unknown as OidcClaims;
}

const BRANCH_RE = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,98}$/;

export function branchFromRef(ref: string): string {
  if (!ref.startsWith("refs/heads/")) throw new OtaError("not_a_branch_ref", 403);
  const branch = ref.slice("refs/heads/".length);
  if (!BRANCH_RE.test(branch)) throw new OtaError("invalid_branch", 403);
  return branch;
}

export function branchSlug(branch: string): string {
  return branch.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 60) || "branch";
}

/** The release tag and asset name the workflow must use; the server recomputes them instead of trusting input. */
export function expectedTag(branch: string, versionCode: number): string {
  return "ota-" + branchSlug(branch) + "-" + versionCode;
}

export function buildApkUrl(repository: string, tag: string, asset: string): string {
  return "https://github.com/" + repository + "/releases/download/" + encodeURIComponent(tag) + "/" +
    encodeURIComponent(asset);
}

/** Registration and branch management are restricted to the repository owner (or listed actors). */
export function isAdminActor(claims: OidcClaims, adminActorsEnv: string | undefined): boolean {
  const configured = (adminActorsEnv ?? "").split(",").map((v) => v.trim().toLowerCase()).filter(Boolean);
  const allowed = configured.length > 0 ? configured : [claims.repository_owner.toLowerCase()];
  return allowed.includes(claims.actor.toLowerCase());
}

/** Accepts a token only from the named workflow file on the very ref the token was issued for. */
export function isWorkflowFile(claims: OidcClaims, repository: string, file: string): boolean {
  const expected = repository.toLowerCase() + "/.github/workflows/" + file.toLowerCase() + "@" +
    claims.ref.toLowerCase();
  return claims.job_workflow_ref.toLowerCase() === expected;
}

export interface PublishInput {
  versionCode: number;
  versionName: string;
  tag: string;
  assetName: string;
  sizeBytes: number;
  sha256: string;
  certSha256: string;
  minSdk: number;
  minSupportedVersionCode: number;
  releaseNotes: string;
  commitSha: string;
  runId: number;
}

function int(value: unknown, min: number, max: number, code: string): number {
  const n = typeof value === "string" && /^\d{1,15}$/.test(value) ? Number(value) : value;
  if (typeof n !== "number" || !Number.isSafeInteger(n) || n < min || n > max) throw new OtaError(code);
  return n;
}

function hex64(value: unknown, code: string): string {
  if (typeof value !== "string" || !/^[A-Fa-f0-9]{64}$/.test(value)) throw new OtaError(code);
  return value.toUpperCase();
}

/** Validates the publish request against the verified OIDC claims. Throws OtaError on any mismatch. */
export function validatePublishBody(body: Record<string, unknown>, claims: OidcClaims, branch: string): PublishInput {
  const versionCode = int(body.version_code, 1, 2_000_000_000, "bad_version_code");
  const versionName = typeof body.version_name === "string" ? body.version_name.trim() : "";
  if (!/^[A-Za-z0-9._+-]{1,40}$/.test(versionName)) throw new OtaError("bad_version_name");
  const tag = typeof body.tag === "string" ? body.tag : "";
  if (tag !== expectedTag(branch, versionCode)) throw new OtaError("bad_tag");
  const assetName = typeof body.asset_name === "string" ? body.asset_name : "";
  if (!/^[A-Za-z0-9._-]{1,120}\.apk$/.test(assetName)) throw new OtaError("bad_asset_name");
  const sizeBytes = int(body.size_bytes, 1, MAX_APK_BYTES, "bad_size");
  const sha256 = hex64(body.sha256, "bad_sha256");
  const certSha256 = hex64(body.cert_sha256, "bad_cert_sha256");
  const minSdk = int(body.min_sdk ?? 26, 1, 99, "bad_min_sdk");
  const minSupported = int(body.min_supported_version_code ?? 1, 1, versionCode, "bad_min_supported");
  const notes = typeof body.release_notes === "string" ? body.release_notes : "";
  if (notes.length > 8000) throw new OtaError("notes_too_long");
  const commitSha = typeof body.commit_sha === "string" ? body.commit_sha.toLowerCase() : "";
  if (!/^[0-9a-f]{40}$/.test(commitSha) || commitSha !== claims.sha.toLowerCase()) throw new OtaError("commit_mismatch", 403);
  const runId = int(body.run_id, 1, Number.MAX_SAFE_INTEGER, "bad_run_id");
  if (String(runId) !== claims.run_id) throw new OtaError("run_mismatch", 403);
  return {
    versionCode, versionName, tag, assetName, sizeBytes, sha256, certSha256, minSdk,
    minSupportedVersionCode: minSupported, releaseNotes: notes, commitSha, runId,
  };
}

/**
 * Confirms, from GitHub's own API response, that the release is public and the asset is fully uploaded with the
 * expected size and SHA-256. Fails closed when GitHub does not report a digest.
 */
export function verifyReleaseAsset(release: Record<string, unknown>, input: PublishInput): void {
  if (release.draft === true) throw new OtaError("release_is_draft", 422);
  if (release.tag_name !== input.tag) throw new OtaError("release_tag_mismatch", 422);
  const assets = Array.isArray(release.assets) ? (release.assets as Array<Record<string, unknown>>) : [];
  const asset = assets.find((a) => a.name === input.assetName);
  if (!asset) throw new OtaError("asset_not_found", 422);
  if (asset.state !== "uploaded") throw new OtaError("asset_not_uploaded", 422);
  if (asset.size !== input.sizeBytes) throw new OtaError("asset_size_mismatch", 422);
  if (typeof asset.digest !== "string" || !asset.digest) throw new OtaError("asset_digest_unavailable", 422);
  if (asset.digest.toLowerCase() !== "sha256:" + input.sha256.toLowerCase()) throw new OtaError("asset_digest_mismatch", 422);
}

/** The release tag must point at the exact commit the verified workflow run built. */
export function verifyTagCommit(tagRef: Record<string, unknown>, commitSha: string): void {
  const object = tagRef.object as Record<string, unknown> | undefined;
  if (!object || object.type !== "commit" || typeof object.sha !== "string") throw new OtaError("tag_not_a_commit", 422);
  if (object.sha.toLowerCase() !== commitSha.toLowerCase()) throw new OtaError("tag_commit_mismatch", 422);
}

/** A branch is derived from the default branch when GitHub reports a common ancestor for the pair. */
export function verifyDerivedBranch(branch: string, compare: Record<string, unknown>): void {
  if (["main", "master"].includes(branch.toLowerCase())) throw new OtaError("branch_not_allowed", 403);
  const base = compare.merge_base_commit as Record<string, unknown> | undefined;
  if (!base || typeof base.sha !== "string") throw new OtaError("branch_not_derived", 422);
  if (!["ahead", "identical", "diverged", "behind"].includes(String(compare.status))) {
    throw new OtaError("branch_not_derived", 422);
  }
}
