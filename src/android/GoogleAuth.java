package com.andreszs.cordova.googleauth;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Log;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;
import com.google.android.gms.common.api.ApiException;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class GoogleAuth extends CordovaPlugin {
    private static final String TAG = "GoogleAuth";
    private static final int LEGACY_SIGN_IN_REQUEST = 41071;

    private CredentialManager credentialManager;
    private boolean loginInProgress;
    private CallbackContext legacyCallbackContext;

    @Override
    protected void pluginInitialize() {
        credentialManager = CredentialManager.create(cordova.getContext());
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (!"login".equals(action)) {
            return false;
        }

        JSONObject options = args.optJSONObject(0);
        String serverClientId = options == null ? "" : options.optString("serverClientId", "").trim();
        if (serverClientId.isEmpty()) {
            callbackContext.error("MISSING_SERVER_CLIENT_ID");
            return true;
        }

        synchronized (this) {
            if (loginInProgress) {
                callbackContext.error("LOGIN_IN_PROGRESS");
                return true;
            }
            loginInProgress = true;
        }

        cordova.getThreadPool().execute(() -> {
            // Credential Manager depende de un proveedor de Play Services que
            // presenta fallas internas en Android 6 y anteriores. El selector
            // clasico devuelve el mismo ID token y es la ruta compatible.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
                startLegacyLogin(serverClientId, callbackContext);
            } else {
                startCredentialManagerLogin(serverClientId, callbackContext);
            }
        });
        return true;
    }

    private void startCredentialManagerLogin(String serverClientId, CallbackContext callbackContext) {
        try {
            GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                    .setServerClientId(serverClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build();

            GetCredentialRequest request = new GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build();

            credentialManager.getCredentialAsync(
                    cordova.getActivity(),
                    request,
                    new CancellationSignal(),
                    cordova.getThreadPool(),
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse response) {
                            finishLogin();
                            returnCredential(response, callbackContext);
                        }

                        @Override
                        public void onError(GetCredentialException error) {
                            if (error instanceof GetCredentialCancellationException) {
                                finishWithError(callbackContext, "CANCELED");
                                return;
                            }

                            if (error instanceof NoCredentialException
                                    || error instanceof GetCredentialProviderConfigurationException) {
                                Log.w(TAG, "Credential Manager could not supply Google credentials ("
                                        + error.getClass().getSimpleName()
                                        + "); trying legacy Google Sign-In.");
                                startLegacyLogin(serverClientId, callbackContext);
                                return;
                            }

                            String message = error.getErrorMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getErrorMessage().toString();
                            Log.w(TAG, "Credential Manager login failed: "
                                    + error.getClass().getSimpleName() + " / " + error.getType());
                            finishWithError(callbackContext, error.getType() + ": " + message);
                        }
                    }
            );
        } catch (Exception error) {
            Log.w(TAG, "Unable to start Credential Manager; trying legacy Google Sign-In.", error);
            startLegacyLogin(serverClientId, callbackContext);
        }
    }

    /**
     * Credential Manager delegates Google credentials to Play Services. Older
     * Android versions, and some Play Services states after an update, can
     * report NoCredentialException even when a Google account exists. The
     * legacy account chooser is retained solely as a compatibility bridge and
     * returns an ID token with the same server client ID as its audience.
     */
    private void startLegacyLogin(String serverClientId, CallbackContext callbackContext) {
        Activity activity = cordova.getActivity();
        if (activity == null || activity.isFinishing()) {
            finishWithError(callbackContext, "ACTIVITY_NOT_AVAILABLE");
            return;
        }

        activity.runOnUiThread(() -> {
            try {
                GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestEmail()
                        .requestIdToken(serverClientId)
                        .build();
                GoogleSignInClient client = GoogleSignIn.getClient(activity, options);

                client.signOut().addOnCompleteListener(activity, ignored -> {
                    try {
                        if (activity.isFinishing()) {
                            finishWithError(callbackContext, "ACTIVITY_NOT_AVAILABLE");
                            return;
                        }

                        legacyCallbackContext = callbackContext;
                        cordova.setActivityResultCallback(this);
                        activity.startActivityForResult(client.getSignInIntent(), LEGACY_SIGN_IN_REQUEST);
                    } catch (Exception error) {
                        legacyCallbackContext = null;
                        Log.e(TAG, "Unable to launch legacy Google Sign-In.", error);
                        finishWithError(callbackContext, "LEGACY_GOOGLE_SIGN_IN_UNAVAILABLE");
                    }
                });
            } catch (Exception error) {
                Log.e(TAG, "Unable to configure legacy Google Sign-In.", error);
                finishWithError(callbackContext, "LEGACY_GOOGLE_SIGN_IN_UNAVAILABLE");
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode != LEGACY_SIGN_IN_REQUEST || legacyCallbackContext == null) {
            return;
        }

        CallbackContext callbackContext = legacyCallbackContext;
        legacyCallbackContext = null;

        if (resultCode == Activity.RESULT_CANCELED && intent == null) {
            finishWithError(callbackContext, "CANCELED");
            return;
        }

        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(intent)
                    .getResult(ApiException.class);
            String idToken = account.getIdToken();
            if (idToken == null || idToken.isEmpty()) {
                finishWithError(callbackContext, "MISSING_ID_TOKEN");
                return;
            }

            JSONObject result = new JSONObject();
            result.put("idToken", idToken);
            result.put("email", account.getEmail());
            result.put("displayName", account.getDisplayName());
            finishLogin();
            callbackContext.success(result);
        } catch (ApiException error) {
            if (error.getStatusCode() == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
                finishWithError(callbackContext, "CANCELED");
            } else {
                Log.w(TAG, "Legacy Google Sign-In failed with status " + error.getStatusCode());
                finishWithError(callbackContext, "LEGACY_GOOGLE_SIGN_IN_" + error.getStatusCode());
            }
        } catch (Exception error) {
            Log.e(TAG, "Invalid legacy Google credential.", error);
            finishWithError(callbackContext, "INVALID_GOOGLE_CREDENTIAL");
        }
    }

    private void returnCredential(GetCredentialResponse response, CallbackContext callbackContext) {
        try {
            Credential credential = response.getCredential();
            if (!(credential instanceof CustomCredential)
                    || (!GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())
                    && !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL.equals(credential.getType()))) {
                callbackContext.error("UNEXPECTED_CREDENTIAL_TYPE");
                return;
            }

            GoogleIdTokenCredential googleCredential = GoogleIdTokenCredential.createFrom(credential.getData());
            String idToken = googleCredential.getIdToken();
            if (idToken == null || idToken.isEmpty()) {
                callbackContext.error("MISSING_ID_TOKEN");
                return;
            }

            JSONObject result = new JSONObject();
            result.put("idToken", idToken);
            result.put("email", googleCredential.getId());
            result.put("displayName", googleCredential.getDisplayName());
            callbackContext.success(result);
        } catch (Exception error) {
            Log.e(TAG, "Invalid Credential Manager Google credential.", error);
            callbackContext.error("INVALID_GOOGLE_CREDENTIAL");
        }
    }

    private void finishWithError(CallbackContext callbackContext, String error) {
        finishLogin();
        callbackContext.error(error);
    }

    private synchronized void finishLogin() {
        loginInProgress = false;
    }
}
