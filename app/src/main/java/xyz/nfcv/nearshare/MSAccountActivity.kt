package xyz.nfcv.nearshare

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.android.volley.Response
import com.android.volley.VolleyError
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.android.synthetic.main.activity_msaccount.*
import org.json.JSONObject

class MSAccountActivity : AppCompatActivity() {

    /* Azure AD Variables */
    private var mSingleAccountApp: ISingleAccountPublicClientApplication? = null
    private var mAccount: IAccount? = null

    private var mMSGraphResourceURL = MSGraphRequestWrapper.MS_GRAPH_ROOT_ENDPOINT + "v1.0/me"

    private val scopes = arrayOf("user.read", "profile", "email")

    private fun signIn() {
        mSingleAccountApp?.signIn(this, null, scopes, getAuthInteractiveCallback())
    }

    private fun signOut() {
        /**
         * Removes the signed-in account and cached tokens from this app (or device, if the device is in shared mode).
         */
        mSingleAccountApp?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                mAccount = null
                showToastOnSignOut()
            }

            override fun onError(exception: MsalException) {
                displayError(exception)
            }
        })
    }

    private fun callGraphApiInteractive() {
        /**
         * If acquireTokenSilent() returns an error that requires an interaction (MsalUiRequiredException),
         * invoke acquireToken() to have the user resolve the interrupt interactively.
         *
         * Some example scenarios are
         * - password change
         * - the resource you're acquiring a token for has a stricter set of requirement than your Single Sign-On refresh token.
         * - you're introducing a new scope which the user has never consented for.
         */
        mSingleAccountApp?.acquireToken(this, scopes, getAuthInteractiveCallback())
    }

    private fun callGraphApiSilent() {
        /**
         * Once you've signed the user in,
         * you can perform acquireTokenSilent to obtain resources without interrupting the user.
         */
        mSingleAccountApp?.acquireTokenSilentAsync(scopes, mAccount!!.authority, getAuthSilentCallback())
    }


    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_msaccount)

        PublicClientApplication.createSingleAccountPublicClientApplication(this, R.raw.auth_config_single_account,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    /**
                     * This test app assumes that the app is only going to support one account.
                     * This requires "account_mode" : "SINGLE" in the config json file.
                     **/
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        mSingleAccountApp = application
                        loadAccount()
                    }

                    override fun onError(exception: MsalException) = displayError(exception)
                })

        btn_signin.setOnClickListener { signIn() }
        btn_signout.setOnClickListener { signOut() }
        btn_a.setOnClickListener { callGraphApiInteractive() }
        btn_b.setOnClickListener { callGraphApiSilent() }
    }

    private fun test() {
        Log.d(javaClass.name, "test something")
    }

    override fun onResume() {
        super.onResume()
        /**
         * The account may have been removed from the device (if broker is in use).
         *
         * In shared device mode, the account might be signed in/out by other apps while this app is not in focus.
         * Therefore, we want to update the account state by invoking loadAccount() here.
         */
        loadAccount()
    }


    /**
     * Callback used in for silent acquireToken calls.
     */
    private fun getAuthSilentCallback(): SilentAuthenticationCallback {
        return object : SilentAuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                Log.d(javaClass.name, "Successfully authenticated")

                /* Successfully got a token, use it to call a protected resource - MSGraph */
                callGraphAPI(authenticationResult)
            }

            override fun onError(exception: MsalException) {
                /* Failed to acquireToken */
                Log.d(javaClass.name, "Authentication failed: $exception")
                displayError(exception)
                when (exception) {
                    is MsalClientException -> {
                        /* Exception inside MSAL, more info inside MsalError.java */
                        exception.printStackTrace()
                    }
                    is MsalServiceException -> {
                        /* Exception when communicating with the STS, likely config issue */
                        exception.printStackTrace()
                    }
                    is MsalUiRequiredException -> {
                        /* Tokens expired or no session, retry with interactive */
                        exception.printStackTrace()
                    }
                }
            }
        }
    }


    /**
     * Callback used for interactive request.
     * If succeeds we use the access token to call the Microsoft Graph.
     * Does not check cache.
     */
    private fun getAuthInteractiveCallback(): AuthenticationCallback {
        return object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                /* Successfully got a token, use it to call a protected resource - MSGraph */
                Log.d(javaClass.name, "Successfully authenticated")
                Log.d(javaClass.name, "ID Token: " + authenticationResult.account.claims!!["id_token"])

                /* Update account */
                mAccount = authenticationResult.account
                Log.d(javaClass.name, "DeviceMode: " + if (mSingleAccountApp?.isSharedDevice == true) "Shared" else "Non-shared")

                /* call graph */
                callGraphAPI(authenticationResult)
            }

            override fun onError(exception: MsalException) {
                /* Failed to acquireToken */
                Log.d(javaClass.name, "Authentication failed: $exception")
                displayError(exception)
                if (exception is MsalClientException) {
                    /* Exception inside MSAL, more info inside MsalError.java */
                    exception.printStackTrace()
                } else if (exception is MsalServiceException) {
                    /* Exception when communicating with the STS, likely config issue */
                    exception.printStackTrace()
                }
            }

            override fun onCancel() {
                /* User canceled the authentication */
                Log.d(javaClass.name, "User cancelled login.")
            }
        }
    }


    /**
     * Load the currently signed-in account, if there's any.
     */
    private fun loadAccount() {
        mSingleAccountApp?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                // You can use the account data to update your UI or your app database.
                mAccount = activeAccount
                Log.d(javaClass.name, "DeviceMode: " + if (mSingleAccountApp?.isSharedDevice == true) "Shared" else "Non-shared")
            }

            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                if (currentAccount == null) {
                    // Perform a cleanup task as the signed-in account changed.
                    showToastOnSignOut()
                }
            }

            override fun onError(exception: MsalException) {
                displayError(exception)
            }
        })
    }

    /**
     * Display the error message
     */
    private fun displayError(exception: Exception) {
        Log.d(javaClass.name, exception.toString())
    }

    /**
     * Display the graph response
     */
    private fun displayGraphResult(graphResponse: JSONObject?) {
        account_info.text = graphResponse.toString()
//        Log.d(javaClass.name, graphResponse.toString())
    }

    /**
     * Make an HTTP request to obtain MSGraph data
     */
    private fun callGraphAPI(authenticationResult: IAuthenticationResult) {
        MSGraphRequestWrapper.callGraphAPIUsingVolley(
                this,
                mMSGraphResourceURL,
                authenticationResult.accessToken,
                object : Response.Listener<JSONObject?> {
                    override fun onResponse(response: JSONObject?) {
                        /* Successfully called graph, process data and send to UI */
                        Log.d(javaClass.name, "Response: $response")
                        displayGraphResult(response)
                    }
                },
                object : Response.ErrorListener {
                    override fun onErrorResponse(error: VolleyError) {
                        Log.d(javaClass.name, "Error: $error")
                    }
                })
    }

    /**
     * Updates UI when app sign out succeeds
     */
    private fun showToastOnSignOut() {
        Toast.makeText(this, "Signed Out.", Toast.LENGTH_SHORT).show()
    }
}