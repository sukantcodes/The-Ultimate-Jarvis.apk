# JARVIS Home Hub

Gateway for controlling devices on your home network when your phone is away. Outbound HTTPS only; nothing listens on a port.

1. Apply `supabase/migrations/20261117000000_jarvis_home.sql` to the JARVIS-CLOUD_CLI project.
2. `pip install -r requirements.txt` (Python 3.10+; Windows 11 first).
3. Copy `config.example.json` to `%APPDATA%\JarvisHomeHub\config.json` and paste the **publishable** key (never a service_role key).
4. `python jarvis_home_hub.py login` (your JARVIS account), then `python jarvis_home_hub.py run`.
5. Optional password for a LAN device: `python jarvis_home_hub.py set-device-password <device_id>` (stored in the OS vault).
6. In the app: Home Settings → Home Hub Configuration → Check Home Hubs. Turn on "Use Home Hub if the phone can't reach it" per device.

Supports Tasmota, Shelly Gen1, custom HTTP URLs, and BLE writes (optional `bleak`). Run tests: `python -m unittest test_hub`.
To start at logon on Windows, create a Task Scheduler task running `python jarvis_home_hub.py run`.
