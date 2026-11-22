# jNet

Android host for a SOCKS5 proxy over current Wi-Fi or Wi-Fi Direct. Requires Android 13+.

## Set up an offline device

Start sharing, join the shared Wi-Fi, then scan **Setup page QR** with the other device's camera or type the displayed setup URL into its browser. Use the complete URL including the port. The phone streams official client downloads over its internet connection, including FlClash for Android, Windows, Linux and macOS. Existing v2rayN (Windows/Linux), NekoBox (Android) and Potatso (iOS) choices are retained. An automatic sign-in popup depends on the client and network. Use **Proxy QR** to import SOCKS5 settings into compatible clients.

### Recommended FlClash setup (Android, Windows, Linux, macOS)

1. On jNet's local setup page, select your platform and download FlClash. FlClash is the default choice for those platforms. FlClash is **not available on iOS**; use Potatso there instead.
2. Tap **Import into FlClash**. The page hosts a complete Mihomo YAML profile with jNet's actual SOCKS5 host, port, UDP relay, DNS, a jNet proxy group, direct LAN rules and an internet catch-all rule. You can also **Download YAML** or **Copy profile URL** to import through FlClash's Profiles screen. Captive sign-in minibrowsers may block opening apps; use a full browser if needed.
3. If jNet has a SOCKS5 password, enter it in the setup page first. The portal checks the supplied password and returns an unguessable **10-minute import URL**. The password is never published in the general setup page or public profile URL. Use this HTTP setup page on a trusted local network, since the token and password travel over local HTTP. A token will expire and the configuration must be reimported when the jNet host address changes.
4. Apply the imported profile and use **Rule** routing. On Android, open **Dashboard → Edit**, add the **VPN** button if it is missing, then enable VPN and approve Android's VPN permission. On desktop, enable **TUN** and authorize elevation if prompted. A YAML import cannot turn on FlClash's VPN button or grant operating system permissions.
5. Open a website and verify jNet's traffic counters increase. If traffic bypasses jNet, confirm the profile is selected, routing is **Rule** rather than Direct, VPN/TUN is on, and the phone's IP is still reachable outside the tunnel.

When no proxy password is configured, the generated profile is available at `http://PHONE_IP:PORT/flclash.yaml`, where PORT is the **setup portal** port printed by jNet, not SOCKS5 port 1080. The profile contains the live SOCKS5 endpoint, so it should not be copied from a different hosting session.



## Connect a computer

For an offline router, connect both devices to its regular Wi-Fi network. On the phone, enable mobile data, select **Use mobile data for proxy traffic**, and tap **Share on current Wi-Fi**. If you set a proxy password, enter `jnet` as the SOCKS5 username. In Potatso or NekoBox, scan **Show setup QR** to import the proxy. On a computer, enter the displayed address and port in your SOCKS5 client.

Start by loading a page through the configured client. For a UDP test, use a client with SOCKS5 UDP relay on and check that **Last UDP relay** changes. To test recovery, briefly disconnect and rejoin the phone's Wi-Fi, then check for new activity. If the proxy address changes, update the client. A router with client isolation can block the PC from reaching the phone.

**Traffic** counts relayed payload bytes and shows live send and receive rates. The graph and peak rates cover the time the app screen has been sampling. **Share diagnostics** previews a report with the proxy address, upstream status, aggregate traffic, and recent events. The report excludes passwords, client addresses, and traffic destinations.
Start Wi-Fi Direct and use your computer's Wi-Fi settings to join the network shown in jNet.

If both devices already use the same trusted router, tap **Share on current Wi-Fi** instead. Use the address shown in jNet or scan its setup QR. The router can be offline if it allows devices to communicate locally and the phone keeps a working internet connection, such as mobile data. This mode uses Android's normal outbound routing. Devices that can reach the proxy can use it without a separate login.

Firefox on Windows or Linux: **Settings → General → Network Settings → Settings → Manual proxy configuration**. Enter the displayed IP and port in **SOCKS Host**, choose **SOCKS v5**, and enable **Proxy DNS when using SOCKS v5**. This routes Firefox traffic only. For a quick terminal check on either platform, use:

    curl --proxy socks5h://PHONE_IP:1080 https://example.com

Use `curl.exe` in Windows PowerShell if `curl` resolves to an alias.

### Windows TUN setup

1. Join the Wi-Fi network shown in jNet. Open [v2rayN for Windows](https://github.com/2dust/v2rayN/releases/latest).
2. Choose **Add [SOCKS]** in the configuration menu. Enter the address shown in jNet and port **1080**. If proxy authentication is enabled, enter username **jnet** and the SOCKS5 password.
3. Save the profile and set it active. Choose **Global** proxy routing and turn on **Enable Tun**. Approve the Windows administrator prompt if requested.

### Join a tailnet from a Windows client

With the PC connected only to the phone's Wi-Fi Direct network, complete the Windows TUN setup above. Leave the phone on its internet connection. It does not need to join the tailnet. Keep the jNet SOCKS endpoint reachable directly over Wi-Fi.

1. Load a new website on the PC and check that jNet's traffic counters increase. This tests the PC's internet path independently of Tailscale.
2. Open Tailscale on the PC and sign in with an account authorized on the server's tailnet. Leave the exit node unset. Run `tailscale ip -4` to see the PC's own tailnet address.
3. In PowerShell run `Find-NetRoute -RemoteIPAddress SERVER_IP`, substituting the server's numeric Tailscale IPv4 address. Windows should select the Tailscale interface for the server. Run the same check for the phone's local proxy IP; that destination should use Wi-Fi. If the proxy TUN captures the server address, exclude that address from its TUN routes so Windows can select Tailscale. Tailscale's public connections still need internet through jNet.
4. Run `tailscale ping SERVER_IP`. A direct or relayed pong confirms peer connectivity. Then connect Minecraft to the server's tailnet IP and configured game port. If the peer responds but the game cannot join, check the tailnet access rules, server listener and host firewall for that port. A LAN-only server needs an accessible subnet route or Tailscale installed on the server.

This workflow requires compatible Windows routes. End-to-end operation with Wi-Fi Direct as the PC's sole internet path has not yet been verified.

SOCKS supports TCP CONNECT in all modes. Current Wi-Fi and Wi-Fi Direct also support UDP ASSOCIATE for clients that enable it. The client must keep its TCP association open and send unfragmented SOCKS datagrams. Use any compatible SOCKS5 client. Choose a client with TUN or VPN mode if you want it to route eligible apps without configuring each app separately.

jNet retries hosting after a network interruption until you tap **Stop sharing**. Clients reconnect using their own retry settings. If the proxy address changes, copy the new address or scan the updated setup QR.

## Build and release

With JDK 17, Gradle 8.9, and Android SDK 35, run `gradle :app:assembleDebug :app:lintDebug`.

To release, increment `versionCode`, update `versionName`, then tag the commit `v<versionName>`. The workflow builds, verifies the signature and attaches the Android APK to a prerelease. Configure `JNET_KEYSTORE_BASE64` and `JNET_KEYSTORE_PASSWORD` once using a PKCS#12 signing key with alias `jnet`. Keep the same key for app updates.
