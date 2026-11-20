# JARVIS Windows companion agent

Joins the JARVIS Device Network as a **child** device. Status: implemented, **not yet run on a physical Windows PC**.
Crypto/protocol parts are unit-tested (`python -m unittest discover -s tests`), and a signature produced here was verified by the server's own verification code.

Setup: `pip install -r requirements.txt`, then
`python jarvis_udn_agent.py --url https://<ref>.supabase.co --key <publishable key>` and sign in with your JARVIS account.
Only the publishable key is used; the service-role key is never needed. The PC stays uncontrollable until a parent approves it in Device Network.

Capabilities: device.info, device.battery, device.connectivity, device.lock, app.launch (allow-list only), notify.show.
Sensitive (consent-required) commands are rejected because this agent cannot show an on-device approval prompt.
Secrets (device private key, refresh token) are protected with Windows DPAPI; the agent refuses to run elsewhere.
