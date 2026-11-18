# VK ID setup for XXW Music

The project is configured for VK ID application `54906914`.

Android settings expected by the VK cabinet:
- Package: `com.musicdl.android`
- Redirect scheme: `vk54906914`
- Redirect host: `vk.ru`

Before building, open `android/local.properties` and add:

    VKIDClientID=54906914
    VKIDClientSecret=<your protected key>

Do not put the protected key into source control or send it in chat.

The previous WebView OAuth implementation was removed. XXW Music now uses the official VK ID Android SDK 2.7.3, which handles the VK app/browser hand-off and redirect back into the Android app.

Important: the protected key shown in the VK cabinet screenshot was exposed. Rotate/regenerate it in the VK cabinet before using the new build, then put the new value into `local.properties`.
