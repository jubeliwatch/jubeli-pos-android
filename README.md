# Jubeli POS (Android)

A thin Android wrapper (WebView) around the Jubeliwatch store POS at <https://jubeliwatch.github.io/admin.html>.
It opens the live site, so new site features appear without reinstalling. Reinstall only when the app itself changes
(permissions, printing, icon, or the share bridge).

What the app adds over the web version: camera photos, downloads saved to `Download/Jubeliwatch`, direct Bluetooth
58 mm receipt printing, Android print service, the native share sheet for posters, and WhatsApp/map links opening in their apps.

## Download
Open **Releases** and download `jubeli-pos.apk`. Allow installing from your browser when Android asks.

## New release
```
git tag v1.0.1
git push origin v1.0.1
```
GitHub Actions builds, signs, and attaches the APK. **Run workflow** (Actions tab) makes a test build under Artifacts.

## Signing key (important)
The APK is signed with the key in the secrets `KEYSTORE_B64` and `KEYSTORE_PASS` (key alias `jubeli`).
Keep an offline copy: if it is lost, new versions cannot update the installed app (uninstall + reinstall, losing offline data).
Never commit key files to this repo.

## Security of the page-to-app bridge
The page talks to the app through `AyriApp.postMessage(JSON)` (name kept for compatibility with the POS code), installed with
`WebViewCompat.addWebMessageListener` ONLY for `https://jubeliwatch.github.io` in the main frame. File names and types are sanitised,
messages are capped at 24 MB, and external links are limited to http/https/mailto/tel/sms/geo/whatsapp/intent.
