![npm downloads](https://img.shields.io/npm/dt/cordova-plugin-google-auth) ![npm version](https://img.shields.io/npm/v/cordova-plugin-google-auth) ![GitHub package.json version](https://img.shields.io/github/package-json/v/andreszs/cordova-plugin-google-auth?color=FF6D00&label=main&logo=github) ![GitHub license](https://img.shields.io/github/license/andreszs/cordova-plugin-google-auth)

# cordova-plugin-google-auth

Cordova Android bridge for Sign in with Google. It requests an ID token through Android Credential Manager and falls back to the legacy Google Sign-In account chooser when Credential Manager or its Play Services provider cannot supply a credential. User cancellation does not trigger the fallback.

## Table of contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Usage](#usage)

## Requirements

- Cordova 11 and cordova-android 11 or newer
- Android `compileSdk` 34 or newer
- Android API 19 or newer at runtime
- A Google OAuth Web client ID to use as the ID token audience

## Installation

From npm:

```shell
cordova plugin add cordova-plugin-google-auth
```

Or directly from GitHub:

```shell
cordova plugin add https://github.com/andreszs/cordova-plugin-google-auth.git
```

## Usage

```javascript
GoogleAuth.login({
    serverClientId: 'WEB_CLIENT_ID.apps.googleusercontent.com'
}, function (credential) {
    // Send credential.idToken to your backend for verification.
    console.log(credential.email, credential.displayName);
}, function (error) {
    // Handle cancellation or a diagnostic sign-in error.
    console.warn(error);
});
```

The success result contains `idToken`, `email`, and `displayName`. Verify the ID token on your backend, including its audience and issuer, before treating the user as signed in. The Web client ID is public configuration; never embed a Google client secret in the app.

The plugin disables automatic selection and allows all Google accounts configured on the device, so Android presents its account chooser. Before the legacy fallback, it clears the app's cached Google Sign-In selection to show the chooser again.

Errors include `CANCELED`, `MISSING_SERVER_CLIENT_ID`, `LOGIN_IN_PROGRESS`, `MISSING_ID_TOKEN`, `INVALID_GOOGLE_CREDENTIAL`, and diagnostic errors returned by Credential Manager or legacy Google Sign-In.

The `after_prepare` hook removes an obsolete `kotlin-android-extensions` line from the generated Android app's `build.gradle` when that line is present. The hook does not modify application source files.
