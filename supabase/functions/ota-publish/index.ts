import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.45.4";
import {
  ADMIN_WORKFLOW_FILE,
  branchFromRef,
  buildApkUrl,
  DEFAULT_REPOSITORY,
  GITHUB_ISSUER,
  isAdminActor,
  isWorkflowFile,
  type Jwks,
  type OidcClaims,
  OtaError,
  PUBLISH_WORKFLOW_FILE,
  validatePublishBody,
  verifyDerivedBranch,
  verifyGithubOidc,
  verifyReleaseAsset,
  verifyTagCommit,
} from "./logic.ts";

// Receives release metadata from GitHub Actions. Authentication is the workflow's own short-lived GitHub OIDC
// token, so no long-lived secret (and no service-role key) lives in GitHub. APK bytes never touch Supabase.

const REPOSITORY = Deno.env.get("OTA_GITHUB_REPOSITORY") ?? DEFAULT_REPOSITORY;
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });

function admin(): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new OtaError("server_not_configured", 500);
  return createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
}

let jwksCache: { jwks: Jwks; at: number } | null = null;
async function githubJwks(): Promise<Jwks> {
  if (jwksCache && Date.now() - jwksCache.at < 60 * 60 * 1000) return jwksCache.jwks;
  const res = await fetch(GITHUB_ISSUER + "/.well-known/jwks");
  if (!res.ok) throw new OtaError("github_jwks_unavailable", 503);
  const jwks = (await res.json()) as Jwks;
  jwksCache = { jwks, at: Date.now() };
  return jwks;
}

async function github(path: string): Promise<Record<string, unknown> | null> {
  const headers: Record<string, string> = {
    Accept: "application/vnd.github+json",
    "User-Agent": "jarvis-ota-publish",
    "X-GitHub-Api-Version": "2022-11-28",
  };
  const token = Deno.env.get("OTA_GITHUB_TOKEN");
  if (token) headers.Authorization = "Bearer " + token;
  const res = await fetch("https://api.github.com/repos/" + REPOSITORY + path, { headers });
  if (res.status === 404) return null;
  if (!res.ok) throw new OtaError("github_unavailable", 503);
  return (await res.json()) as Record<string, unknown>;
}

async function branchRow(db: SupabaseClient, branch: string) {
  const { data, error } = await db.from("ota_branches").select("branch,is_default,is_enabled").eq("branch", branch).maybeSingle();
  if (error) throw new OtaError("registry_unavailable", 503);
  return data as { branch: string; is_default: boolean; is_enabled: boolean } | null;
}

function rpcFailure(message: string | undefined): OtaError {
  const known: Record<string, [string, number]> = {
    release_version_must_increase: ["release_version_must_increase", 409],
    branch_not_eligible: ["branch_not_eligible", 403],
    branch_not_disableable: ["branch_not_disableable", 409],
    invalid_branch: ["invalid_branch", 400],
  };
  for (const key of Object.keys(known)) if (message?.includes(key)) return new OtaError(known[key][0], known[key][1]);
  return new OtaError("publish_failed", 500);
}

async function handle(req: Request): Promise<Response> {
  if (req.method !== "POST") throw new OtaError("method_not_allowed", 405);
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  if (!supabaseUrl) throw new OtaError("server_not_configured", 500);
  const match = /^Bearer\s+(\S+)$/i.exec(req.headers.get("authorization") ?? "");
  if (!match) throw new OtaError("unauthorized", 401);
  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch (_) {
    throw new OtaError("bad_request", 400);
  }

  const audience = supabaseUrl.replace(/\/$/, "") + "/functions/v1/ota-publish";
  const claims: OidcClaims = await verifyGithubOidc(match[1], { jwks: await githubJwks(), audience, repository: REPOSITORY });
  const db = admin();
  const action = typeof body.action === "string" ? body.action : "";

  if (action === "check_branch") {
    const branch = branchFromRef(claims.ref);
    const row = await branchRow(db, branch);
    const eligible = !!row && row.is_enabled;
    return json({ eligible, branch, is_default: !!row?.is_default, reason: eligible ? undefined : "branch_not_registered" });
  }

  if (action === "publish") {
    if (!["push", "workflow_dispatch"].includes(claims.event_name)) throw new OtaError("event_not_allowed", 403);
    if (!isWorkflowFile(claims, REPOSITORY, PUBLISH_WORKFLOW_FILE)) throw new OtaError("workflow_not_allowed", 403);
    const branch = branchFromRef(claims.ref);
    const row = await branchRow(db, branch);
    if (!row || !row.is_enabled) throw new OtaError("branch_not_eligible", 403);
    const input = validatePublishBody(body, claims, branch);

    const release = await github("/releases/tags/" + encodeURIComponent(input.tag));
    if (!release) throw new OtaError("release_not_found", 422);
    verifyReleaseAsset(release, input);
    const tagRef = await github("/git/ref/tags/" + encodeURIComponent(input.tag));
    if (!tagRef) throw new OtaError("tag_not_found", 422);
    verifyTagCommit(tagRef, input.commitSha);

    const { data, error } = await db.rpc("ota_publish_release", {
      p_branch: branch,
      p_version_code: input.versionCode,
      p_version_name: input.versionName,
      p_tag: input.tag,
      p_asset_name: input.assetName,
      p_apk_url: buildApkUrl(REPOSITORY, input.tag, input.assetName),
      p_size_bytes: input.sizeBytes,
      p_sha256: input.sha256,
      p_cert_sha256: input.certSha256,
      p_min_sdk: input.minSdk,
      p_min_supported_version_code: input.minSupportedVersionCode,
      p_release_notes: input.releaseNotes,
      p_commit_sha: input.commitSha,
      p_run_id: input.runId,
      p_run_url: "https://github.com/" + REPOSITORY + "/actions/runs/" + input.runId,
    });
    if (error || !data) throw rpcFailure(error?.message);
    return json({ ok: true, branch, version_code: input.versionCode }, 201);
  }

  if (action === "register_branch" || action === "disable_branch") {
    if (claims.event_name !== "workflow_dispatch") throw new OtaError("event_not_allowed", 403);
    if (!isWorkflowFile(claims, REPOSITORY, ADMIN_WORKFLOW_FILE)) throw new OtaError("workflow_not_allowed", 403);
    if (!isAdminActor(claims, Deno.env.get("OTA_ADMIN_ACTORS"))) throw new OtaError("actor_not_allowed", 403);
    const caller = branchFromRef(claims.ref);
    const callerRow = await branchRow(db, caller);
    if (!callerRow?.is_default) throw new OtaError("must_run_from_default_branch", 403);
    const target = typeof body.branch === "string" ? body.branch.trim() : "";
    if (!/^[A-Za-z0-9][A-Za-z0-9._/-]{0,98}$/.test(target)) throw new OtaError("invalid_branch", 400);

    if (action === "register_branch") {
      const compare = await github("/compare/" + encodeURIComponent(caller) + "..." + encodeURIComponent(target));
      if (!compare) throw new OtaError("branch_not_found", 422);
      verifyDerivedBranch(target, compare);
      const description = typeof body.description === "string" ? body.description.slice(0, 200) : "";
      const { data, error } = await db.rpc("ota_register_branch", {
        p_branch: target, p_derived_from: caller, p_description: description, p_registered_by: claims.actor,
      });
      if (error || !data) throw rpcFailure(error?.message);
      return json({ ok: true, branch: target, enabled: true }, 201);
    }
    const { data, error } = await db.rpc("ota_disable_branch", { p_branch: target });
    if (error || !data) throw rpcFailure(error?.message);
    return json({ ok: true, branch: target, enabled: false });
  }

  throw new OtaError("unknown_action", 400);
}

Deno.serve(async (req: Request) => {
  try {
    return await handle(req);
  } catch (error) {
    if (error instanceof OtaError) return json({ error: error.code }, error.status);
    return json({ error: "server_error" }, 500);
  }
});
