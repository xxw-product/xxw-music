# XXW Music VPN integration

The app now contains the Android per-app VPN service, server selection UI, and an Xray configuration adapter for VLESS, VMess, Trojan, and Shadowsocks share links.

## Build the Xray Android library

The supplied `AndroidLibXrayLite-26.11.9` source is included in `xray/AndroidLibXrayLite/` with its LGPL-3.0 license and geodata assets. Its `go.mod` declares Go 1.27. The current build environment used to prepare this project did not have the required Go toolchain, Android SDK/NDK, gomobile, or network access, so the AAR could not be built locally.

1. Push this project to a GitHub repository.
2. Open **Actions → Build Android Xray library → Run workflow** (or push a change under `xray/AndroidLibXrayLite/`).
3. Download the `libv2ray-android` workflow artifact.
4. Copy `libv2ray.aar` into `app/libs/libv2ray.aar`.
5. Build the Android app again.

The app uses reflection for the gomobile API so the project can still be opened before the AAR is present. Until `app/libs/libv2ray.aar` is added, pressing Connect will return a clear missing-library error; it will not show a false connected state.

## Routing policy

`MusicVpnService` creates an Android `VpnService` TUN interface and calls `Builder.addAllowedApplication("com.musicdl.android")`. The VPN route is therefore restricted to the XXW Music package. Other installed apps are not added to the allow-list and continue to use the device's normal network.

## Supported connection profiles

The current Xray JSON adapter implements VLESS (including TLS/Reality stream settings), VMess, Trojan, and Shadowsocks. The subscription importer can recognize additional URI schemes, but those extra protocols are not yet connectable through this adapter and will show an explicit unsupported-protocol error.

## Important verification still required

A successful Kotlin/Gradle build and an on-device connection test have not been possible in the preparation environment because the Gradle distribution could not be downloaded (`services.gradle.org` DNS/network unavailable), and no Android SDK/NDK is installed. Before release, test at minimum: Android VPN permission flow, connection to a real server for each supported protocol, DNS, IPv4/IPv6, app-only routing, revoke/stop lifecycle, and network changes.
