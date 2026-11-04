# Authentication (Email, Google, Guest)

JARVIS supports exactly three ways in: **email + password**, **Google** (through Supabase), and **Guest Mode**.
It uses the existing Supabase project **JARVIS-CLOUD_CLI** (`yeggjbjcoaaoqntqyjdv`,
`https://yeggjbjcoaaoqntqyjdv.supabase.co`). Signing in proves identity only; it never grants Pro or any other entitlement.

## How it behaves

| Situation | Result |
| --- | --- |
| Launch with a saved session | Straight into JARVIS (token refreshed if expired). |
| Launch with no session and no earlier choice | Auth screen: Log in, Register, Google, Continue as guest. |
| Continue as guest | JARVIS opens normally. The choice is remembered, so the app never asks again. No Supabase account is created. |
| Drawer > Account (guest) | "Sign in / Create account" returns to the running app after success. |
| Drawer > Account (signed in) | "Log out" revokes the session, clears credentials, returns to the auth screen. Device data is not touched. |
| Refresh token rejected by the server | Session ends; the user continues as a guest and is told. |
| Refresh fails because the phone is offline | Session is kept. Nobody is logged out for being offline. |

A feature that genuinely needs an account can launch `AuthActivity.RequireAccount` (an `ActivityResultContract<Unit, Boolean>`),
then continue when it returns `true`. No current feature requires an account.

## Code map

`app/src/main/java/com/jarvis/assistant/auth/`

- `SupabaseConfig` – URL, publishable key (from `BuildConfig`), redirect URIs.
- `SupabaseAuthClient` – REST calls to Supabase Auth (sign-up, password login, refresh, PKCE code exchange, reset, update password, logout).
- `AuthManager` – state, token refresh (mutex-guarded), callback handling, logout.
- `SessionStore` – session in `EncryptedSharedPreferences` (Android Keystore). If secure storage is unavailable the session is memory-only; tokens are never written to plain preferences.
- `Pkce`, `CallbackParams`, `SupabaseResponseParser`, `AuthValidators`, `AuthModels` – pure Kotlin, unit tested (`app/src/test/.../auth/AuthLogicTest.kt`).

UI: `ui/auth/AuthActivity` (one activity, four in-place modes: Log in, Register, Forgot password, Set new password).

## Security notes

- Only the **publishable** key is in the app. Never use a `service_role` or secret key. The key is injected at build time from `SUPABASE_PUBLISHABLE_KEY`; it is public by design, so it is present in the built APK.
- Google and email-link flows use **PKCE**. The verifier is generated on the device and stored encrypted until the callback. A link containing bare tokens is rejected, so another app cannot forge a login with a custom-scheme link.
- The Google client **secret** lives only in the Supabase dashboard, never in this repo or the app.
- Password-reset responses are identical whether or not the account exists.

## Manual configuration still required

### 1. Supabase redirect URL (needed for Google, email confirmation, password reset)

Dashboard > **Authentication > URL Configuration > Redirect URLs** > add exactly:

```
com.jarvis.assistant://auth-callback/**
```

### 2. Google sign-in

1. Google Cloud Console > **APIs & Services > OAuth consent screen**: configure it. While it is in *Testing*, add the Google accounts that should be able to sign in as test users.
2. **Credentials > Create credentials > OAuth client ID > Application type: Web application** (not Android; the browser flow goes through Supabase).
3. Under **Authorized redirect URIs** add:

   ```
   https://yeggjbjcoaaoqntqyjdv.supabase.co/auth/v1/callback
   ```
4. Copy the Client ID and Client secret.
5. Supabase dashboard > **Authentication > Providers > Google**: enable it and paste the Client ID and secret.

No Android client ID or SHA-1 is needed for this flow.

### 3. Email

- **Authentication > Providers > Email** must be enabled. The app works whether "Confirm email" is on (user is asked to confirm, then log in) or off (user is signed in immediately).
- Supabase's built-in email sender is heavily rate-limited. Configure custom SMTP before real users register.
- New passwords need at least 8 characters in the app. Keep Supabase's minimum password length at 8 or lower so the app's rule is the effective one.

### 4. GitHub

Repository secret `SUPABASE_PUBLISHABLE_KEY` must hold the project's **publishable** key (`sb_publishable_...`, or the legacy `anon` key). It is passed to Gradle as an environment variable by `.github/workflows/Build.yml` and `build.yml`; GitHub masks it in logs. Without it the app still builds and Guest Mode works, and sign-in reports that it is not configured.
