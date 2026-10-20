# Building a signed release APK

A debug APK is signed with Android's shared debug key: fine for your own phone. A release APK is smaller
(R8 shrinks it) and signed with **your** key. You only need one if you want a build you can keep updating without
uninstalling, or to give to someone else.

## 1. Create a key (once, keep it safe)

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore cactusai-release.jks -alias cactusai `
  -keyalg RSA -keysize 4096 -validity 10000
```

If `keytool` is not found, use the one in Android Studio: `"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"`.

Store the `.jks` file somewhere outside the repository, or at least leave it where `.gitignore` ignores it.
**If you lose the key or its passwords you can never update an installed release build; you would have to uninstall
it first (which deletes its models and history).**

## 2. Tell Gradle about it

Create `keystore.properties` in the project root (it is git-ignored; never commit it or paste it into chats):

```properties
storeFile=cactusai-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=cactusai
keyPassword=YOUR_KEY_PASSWORD
```

`storeFile` is relative to the project root.

## 3. Build

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1
```

or `.\gradlew.bat :app:assembleRelease`. The signed file is `app\build\outputs\apk\release\app-release.apk`
and the script copies it to `dist\CactusAI-release.apk` and checks the signature with `apksigner` if it can
find it.

Install: `adb install -r dist\CactusAI-release.apk`. A release build cannot be installed over a debug build
(different signing keys): uninstall one first.

## Notes

* R8 minification is on for release. The JNI binding class is kept by `app/proguard-rules.pro`; if you add
  native methods, keep them there too.
* CI builds an **unsigned** release APK on every push, which proves R8 and resource shrinking succeed, but CI never
  sees your key.
* The engine library is statically linked into the APK. Its licence terms are the vendor's, not this project's;
  see `NOTICE.md` before sharing an APK with anyone.
