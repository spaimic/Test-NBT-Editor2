# Persistent APK signing setup

The GitHub Actions workflow now signs `debug` APKs with one persistent keystore.
This lets Android update an installed NBT Editor APK instead of reporting a package/signature conflict.

## One-time GitHub setup

Create a keystore **outside the repository**. Keep the keystore and passwords private.

Example with a JDK `keytool`:

```bash
keytool -genkeypair -v \
  -keystore persistent-release.jks \
  -alias nbttool \
  -keyalg RSA -keysize 2048 -validity 10000
```

Remember the keystore password, alias, and key password.

Convert the keystore to Base64. On Linux/macOS:

```bash
base64 -w 0 persistent-release.jks > persistent-release.jks.b64
```

On systems without `-w`:

```bash
base64 persistent-release.jks | tr -d '\n' > persistent-release.jks.b64
```

In the GitHub repository, open:

**Settings → Secrets and variables → Actions → New repository secret**

Create these four repository secrets:

- `SIGNING_KEYSTORE_B64` — paste the complete contents of `persistent-release.jks.b64`
- `SIGNING_STORE_PASSWORD` — the keystore password
- `SIGNING_KEY_ALIAS` — usually `nbttool` from the command above
- `SIGNING_KEY_PASSWORD` — the key password

Do **not** commit `persistent-release.jks` or its Base64 contents to the repository.

After the secrets are configured, every GitHub Actions build uses the same signing key.

## Versioning

GitHub Actions uses the workflow run number to create an increasing Android `versionCode`.
The first builds use an offset so they are newer than the original `versionCode 1`.

Version names are:

```text
0.1.<GitHub run number>
```

Manual/local builds without GitHub Actions variables fall back to:

```text
versionCode 2
versionName 0.1.1
```

## Important first-install note

The APK currently installed on the device was signed with the old key. The first APK signed with this new persistent key **cannot replace that old installation**. You will need to uninstall the old app once.

After that one-time transition:

```text
new APK → Install → Update existing app
```

should work without uninstalling each time.
