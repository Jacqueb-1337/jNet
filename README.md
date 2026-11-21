# jNet

Android host for a SOCKS5 proxy over Wi-Fi Direct, Tailscale, or a USB cable. Requires Android 13+. This source preview still needs a build and hardware verification.

## Connect a computer

Start Wi-Fi Direct and use your computer's Wi-Fi settings to join the network shown in jNet.

For Tailscale, connect the phone and client independently using their own Tailscale apps and internet connections. The client needs tailnet access to TCP 1080 on the phone. On the phone, tap **Start tailnet proxy**. jNet looks for the VPN address automatically. If Android does not expose it, copy the IPv4 address from Tailscale and enter it in **Override IPv4 address**. Use **Copy proxy address** to copy the SOCKS5 endpoint. Proxy access is unauthenticated, so only allow clients you want to share with.

If a client uses jNet's Wi-Fi Direct network as its only network connection, the SOCKS5 proxy alone does not provide the client with the network transport that the Tailscale app needs.

Firefox on Windows or Linux: **Settings → General → Network Settings → Settings → Manual proxy configuration**. Enter the displayed IP and port in **SOCKS Host**, choose **SOCKS v5**, and enable **Proxy DNS when using SOCKS v5**. This routes Firefox traffic only. For a quick terminal check on either platform, use:

    curl --proxy socks5h://PHONE_IP:1080 https://example.com

Use `curl.exe` in Windows PowerShell if `curl` resolves to an alias.

For USB, install Android Platform Tools on Windows or Linux, enable USB debugging on the phone, and approve the computer when Android prompts. Start the USB proxy in jNet, then run `adb -d forward tcp:1080 tcp:1080`. Configure your SOCKS client for `127.0.0.1:1080`. To detach the forwarding rule, use `adb -d forward --remove tcp:1080`. This is jNet's own SOCKS transport and requires no PdaNet desktop software.

SOCKS supports TCP CONNECT in every mode. Wi-Fi Direct also supports UDP ASSOCIATE for clients that enable it. The client must keep its TCP association open and send unfragmented SOCKS datagrams. USB and Tailscale modes remain TCP-only. A Tailscale exit node is a separate feature for routing the entire PC through an approved device.

## Build and release

With JDK 17, Gradle 8.9, and Android SDK 35, run `gradle :app:assembleDebug :app:lintDebug`.

To release, increment `versionCode`, update `versionName`, then tag the commit `v<versionName>`. The workflow builds, verifies the signature and attaches the Android APK to a prerelease. Configure `JNET_KEYSTORE_BASE64` and `JNET_KEYSTORE_PASSWORD` once using a PKCS#12 signing key with alias `jnet`. Keep the same key for app updates.

PdaNet wire compatibility and the auto-reconnecting Android VPN client are still pending.
