# jNet

Android host for a SOCKS5 proxy over current Wi-Fi, Wi-Fi Direct, or Tailscale. Requires Android 13+.

## Connect a computer

For an offline router, connect both devices to its regular Wi-Fi network. On the phone, enable mobile data, select **Use mobile data for proxy traffic**, and tap **Share on current Wi-Fi**. If you set a proxy password, enter `jnet` as the SOCKS5 username. In Potatso or NekoBox, scan **Show setup QR** to import the proxy. On a computer, enter the displayed address and port in your SOCKS5 client.

Start by loading a page through the configured client. For a UDP test, use a client with SOCKS5 UDP relay on and check that **Last UDP relay** changes. To test recovery, briefly disconnect and rejoin the phone's Wi-Fi, then check for new activity. If the proxy address changes, update the client. A router with client isolation can block the PC from reaching the phone.

**Traffic** counts relayed payload bytes and shows live send and receive rates. The graph and peak rates cover the time the app screen has been sampling. **Share diagnostics** previews a report with the proxy address, upstream status, aggregate traffic, and recent events. The report excludes passwords, client addresses, and traffic destinations.
Start Wi-Fi Direct and use your computer's Wi-Fi settings to join the network shown in jNet.

If both devices already use the same trusted router, tap **Share on current Wi-Fi** instead. Use the address shown in jNet or scan its setup QR. The router can be offline if it allows devices to communicate locally and the phone keeps a working internet connection, such as mobile data. This mode uses Android's normal outbound routing. Devices that can reach the proxy can use it without a separate login.

For Tailscale, connect the phone and client independently using their own Tailscale apps and internet connections. The client needs tailnet access to TCP 1080 on the phone. On the phone, tap **Start tailnet proxy**. jNet looks for the VPN address automatically. If Android does not expose it, copy the IPv4 address from Tailscale and enter it in **Override IPv4 address**. Use **Copy proxy address** to copy the SOCKS5 endpoint. Proxy access is unauthenticated, so only allow clients you want to share with.

If a client uses jNet's Wi-Fi Direct network as its only network connection, the SOCKS5 proxy alone does not provide the client with the network transport that the Tailscale app needs.

Firefox on Windows or Linux: **Settings → General → Network Settings → Settings → Manual proxy configuration**. Enter the displayed IP and port in **SOCKS Host**, choose **SOCKS v5**, and enable **Proxy DNS when using SOCKS v5**. This routes Firefox traffic only. For a quick terminal check on either platform, use:

    curl --proxy socks5h://PHONE_IP:1080 https://example.com

Use `curl.exe` in Windows PowerShell if `curl` resolves to an alias.

SOCKS supports TCP CONNECT in all modes. Current Wi-Fi and Wi-Fi Direct also support UDP ASSOCIATE for clients that enable it. The client must keep its TCP association open and send unfragmented SOCKS datagrams. Tailscale mode supports TCP. Use any compatible SOCKS5 client. Choose a client with TUN or VPN mode if you want it to route eligible apps without configuring each app separately.

jNet retries hosting after a network interruption until you tap **Stop sharing**. Clients reconnect using their own retry settings. If the proxy address changes, copy the new address or scan the updated setup QR.

## Build and release

With JDK 17, Gradle 8.9, and Android SDK 35, run `gradle :app:assembleDebug :app:lintDebug`.

To release, increment `versionCode`, update `versionName`, then tag the commit `v<versionName>`. The workflow builds, verifies the signature and attaches the Android APK to a prerelease. Configure `JNET_KEYSTORE_BASE64` and `JNET_KEYSTORE_PASSWORD` once using a PKCS#12 signing key with alias `jnet`. Keep the same key for app updates.
