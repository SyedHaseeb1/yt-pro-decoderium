# Signer Folder

This folder contains signing and build tools for APK signing and release builds.

## Structure
- `output/` - Generated signed APKs and build artifacts
- `*.jks`, `*.keystore` - Keystore files (git-ignored, add locally)

## Setup
To sign APKs, place your keystore file here and configure signing in `build.gradle`:

```gradle
signingConfigs {
    release {
        storeFile file('signer/your-keystore.jks')
        storePassword = System.getenv('KEYSTORE_PASSWORD')
        keyAlias = System.getenv('KEY_ALIAS')
        keyPassword = System.getenv('KEY_PASSWORD')
    }
}
```
