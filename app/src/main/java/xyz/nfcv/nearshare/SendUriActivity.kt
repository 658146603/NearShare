package xyz.nfcv.nearshare

import android.Manifest
import android.R.attr.data
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.microsoft.connecteddevices.*
import com.microsoft.connecteddevices.EventListener
import com.microsoft.connecteddevices.remotesystems.*
import com.microsoft.connecteddevices.remotesystems.commanding.RemoteSystemConnectionRequest
import com.microsoft.connecteddevices.remotesystems.commanding.nearshare.NearShareSender
import kotlinx.android.synthetic.main.activity_send_uri.*
import java.lang.ref.WeakReference
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger


class SendUriActivity : AppCompatActivity(), DeviceRecyclerAdapter.OnItemClickListener {
    private lateinit var mPlatform: ConnectedDevicesPlatform
    private lateinit var mRemoteSystemWatcher: RemoteSystemWatcher
    private lateinit var mRemoteDeviceListAdapter: DeviceRecyclerAdapter
    private lateinit var mNearShareSender: NearShareSender
    private var mWatcherStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_uri)
        setSupportActionBar(toolbar)
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.extras?.get(Intent.EXTRA_TEXT)?.toString()?:""
                val matcher = Patterns.WEB_URL.matcher(text)
                if (matcher.find()) {
                    val url = matcher.group()
                    edtContent.setText(url)
                    Log.d(javaClass.name, "url: $url")
                }
                Log.d(javaClass.name, "$data: $text")
            }
        }

        mRemoteDeviceListAdapter = DeviceRecyclerAdapter(applicationContext, this)
        requestPermissions()
        initializePlatform()
        listRemoteSystems.adapter = mRemoteDeviceListAdapter
        listRemoteSystems.layoutManager = LinearLayoutManager(this)
        btnSendUri.setOnClickListener { sendUri() }
        chkProximalDiscovery.setOnClickListener { startOrRestartRemoteSystemWatcher() }
        mNearShareSender = NearShareSender()
    }

    /**
     * Request COARSE_LOCATION permission required for nearshare functionality over bluetooth.
     */
    private fun requestPermissions() {
        // Request user permission for app to use location services, which is a requirement for Bluetooth.
        val rng = Random()
        val permissionRequestCode = rng.nextInt(128)
        val permissionCheck = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (permissionCheck == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), permissionRequestCode)
        } else {
            LOG.log(Level.INFO, "Requested User Permission To Enable NearShare Prerequisites")
        }
    }

    /**
     * Initialize the platform. This is required before we attempt to use CDP SDK.
     * Steps to start platform:
     * 1. Initialize platform
     * 2. Request Access Token
     * 3. Start Platform
     */
    private fun initializePlatform() {
        mPlatform = ConnectedDevicesPlatform(applicationContext)
        val accountManager = mPlatform.accountManager

        // This subscription isn't necessary for NearShare because it depends on an Anonymous account;
        // but is added in the sample for completeness, in case the app supports other accounts.
        accountManager.accessTokenRequested().subscribe { sender: ConnectedDevicesAccountManager, args: ConnectedDevicesAccessTokenRequestedEventArgs -> onAccessTokenRequested(sender, args) }
        accountManager.accessTokenInvalidated().subscribe { sender: ConnectedDevicesAccountManager, args: ConnectedDevicesAccessTokenInvalidatedEventArgs -> onAccessTokenInvalidated(sender, args) }
        // Subscribe to NotificationRegistrationStateChanged event
        mPlatform.notificationRegistrationManager.notificationRegistrationStateChanged().subscribe { sender: ConnectedDevicesNotificationRegistrationManager, args: ConnectedDevicesNotificationRegistrationStateChangedEventArgs -> onNotificationRegistrationStateChanged(sender, args) }
        mPlatform.start()

        // After platform start, before we can start remotesystem discovery, need to addaccount,
        // NearShare only requires anonymous account, other CDP scenarios may require adding signed in
        // accounts.
        createAndAddAnonymousAccount(mPlatform)
    }
    // region TokenRegistrationCallback
    /**
     * This event is fired when there is a need to request a token. This event should be subscribed and ready to respond before any request is sent out.
     *
     * @param sender ConnectedDevicesAccountManager which is making the request
     * @param args   Contains arguments for the event
     */
    private fun onAccessTokenRequested(sender: ConnectedDevicesAccountManager, args: ConnectedDevicesAccessTokenRequestedEventArgs) {
        LOG.log(Level.INFO, "Token Access Requested")
    }

    /**
     * This event is fired when a token consumer reports a token error. The token provider needs to
     * either refresh their token cache or request a new user login to fix their account setup.
     * If access token in invalidated, refresh token and renew access token.
     *
     * @param sender ConnectedDevicesAccountManager which is making the request
     * @param args   Contains arguments for the event
     */
    private fun onAccessTokenInvalidated(sender: ConnectedDevicesAccountManager, args: ConnectedDevicesAccessTokenInvalidatedEventArgs) {
        LOG.log(Level.INFO, "Token invalidated for account")
    }
    // endregion TokenRegistrationCallback
    /**
     * NearShare just works with anonymous account, signed in accounts are needed when using other CDP
     * features.
     */
    private fun createAndAddAnonymousAccount(platform: ConnectedDevicesPlatform) {
        val account = ConnectedDevicesAccount.getAnonymousAccount()
        platform.accountManager.addAccountAsync(account).whenComplete { result: ConnectedDevicesAddAccountResult?, throwable: Throwable? ->
            if (throwable != null) {
                LOG.log(Level.SEVERE, String.format("AccountManager addAccountAsync returned a throwable: %1\$s", throwable.message))
            } else {
                LOG.log(Level.INFO, "AccountManager : Added account successfully")
            }
        }
    }

    /**
     * Event for when the registration state changes for a given account.
     *
     * @param sender ConnectedDevicesNotificationRegistrationManager which is making the request
     * @param args   Contains arguments for the event
     */
    private fun onNotificationRegistrationStateChanged(sender: ConnectedDevicesNotificationRegistrationManager, args: ConnectedDevicesNotificationRegistrationStateChangedEventArgs) {
        LOG.log(Level.INFO, "NotificationRegistrationStateChanged for account")
    }

    /**
     * This method starts the RemoteSystem discovery process. It sets the corresponding filters
     * to ensure that only spatially proximal devices are listed. It also sets up listeners
     * for important events, such as device added, device updated, and device removed
     */
    private fun startOrRestartRemoteSystemWatcher() {
        val filters = ArrayList<RemoteSystemFilter>()
        if (chkProximalDiscovery.isChecked) {
            filters.add(RemoteSystemDiscoveryTypeFilter(RemoteSystemDiscoveryType.PROXIMAL))
        } else {
            filters.add(RemoteSystemDiscoveryTypeFilter(RemoteSystemDiscoveryType.SPATIALLY_PROXIMAL))
        }
        filters.add(RemoteSystemStatusTypeFilter(RemoteSystemStatusType.ANY))
        filters.add(RemoteSystemAuthorizationKindFilter(RemoteSystemAuthorizationKind.ANONYMOUS))
        mRemoteSystemWatcher = RemoteSystemWatcher(filters)
        val weakRemoteSystemWatcher = WeakReference(mRemoteSystemWatcher)
        weakRemoteSystemWatcher.get()?.remoteSystemAdded()?.subscribe(RemoteSystemAddedListener())
        weakRemoteSystemWatcher.get()?.remoteSystemUpdated()?.subscribe(RemoteSystemUpdatedListener())
        weakRemoteSystemWatcher.get()?.remoteSystemRemoved()?.subscribe(RemoteSystemRemovedListener())
        weakRemoteSystemWatcher.get()?.errorOccurred()?.subscribe(RemoteSystemWatcherErrorOccurredListener())

        // Everytime user toggles checkbox Proximal discovery
        // we restart the watcher with appropriate filters to wither do a
        // Proximal or Spatially Proximal discovery. this check is to see if watcher has been previously started
        // if was started, we stop it and restart with the new set of filters
        if (mWatcherStarted) {
            weakRemoteSystemWatcher.get()?.stop()
            mWatcherStarted = false
            mRemoteDeviceListAdapter.clear()
        }
        weakRemoteSystemWatcher.get()?.start()
        mWatcherStarted = true
    }

    /**
     * Send URI to the target device using nearshare
     */
    private fun sendUri() {
        val uriText = edtContent.text.toString()
        val remoteSystem = mRemoteDeviceListAdapter.mSelected
        if (null != remoteSystem) {
            val remoteSystemConnectionRequest = RemoteSystemConnectionRequest(remoteSystem)
            if (mNearShareSender.isNearShareSupported(remoteSystemConnectionRequest)) {
                mNearShareSender.sendUriAsync(remoteSystemConnectionRequest, uriText)
            } else {
                LOG.log(Level.SEVERE, "NearShare is not supported in this device")
            }
        } else {
            LOG.log(Level.SEVERE, "Please Select a Remote System to SendUri")
        }
    }

    // region HelperClasses
    private inner class RemoteSystemAddedListener : EventListener<RemoteSystemWatcher?, RemoteSystemAddedEventArgs> {
        override fun onEvent(remoteSystemWatcher: RemoteSystemWatcher?, args: RemoteSystemAddedEventArgs) {
            val remoteSystemParam = args.remoteSystem
            LOG.log(Level.INFO, String.format("Adding system: %1\$s", args.remoteSystem.displayName))
            // Calls from the OneSDK are not guaranteed to come back on the given (UI) thread
            // hence explicitly call runOnUiThread
            runOnUiThread {
                mRemoteDeviceListAdapter.addDevice(remoteSystemParam)
            }
        }
    }

    private inner class RemoteSystemUpdatedListener : EventListener<RemoteSystemWatcher?, RemoteSystemUpdatedEventArgs> {
        override fun onEvent(remoteSystemWatcher: RemoteSystemWatcher?, args: RemoteSystemUpdatedEventArgs) {
            LOG.log(Level.INFO, String.format("Updating system: %1\$s", args.remoteSystem.displayName))
        }
    }

    private inner class RemoteSystemRemovedListener : EventListener<RemoteSystemWatcher?, RemoteSystemRemovedEventArgs> {
        override fun onEvent(remoteSystemWatcher: RemoteSystemWatcher?, args: RemoteSystemRemovedEventArgs) {
            val remoteSystemParam = args.remoteSystem
            LOG.log(Level.INFO, String.format("Removing system: %1\$s", args.remoteSystem.displayName))
            // Calls from the OneSDK are not guaranteed to come back on the given (UI) thread
            // hence explicitly call runOnUiThread
            runOnUiThread {
                mRemoteDeviceListAdapter.removeDevice(remoteSystemParam)
            }
        }
    }

    private inner class RemoteSystemWatcherErrorOccurredListener : EventListener<RemoteSystemWatcher?, RemoteSystemWatcherErrorOccurredEventArgs> {
        override fun onEvent(remoteSystemWatcher: RemoteSystemWatcher?, args: RemoteSystemWatcherErrorOccurredEventArgs) {
            LOG.log(Level.SEVERE, String.format("Discovery error: %1\$s", args.error.toString()))
        }
    }
    // endregion HelperClasses

    companion object {
        // region Member Variables
        private val LOG = Logger.getLogger(MainActivity::class.java.simpleName)
    }

    override fun onItemClick(clazz: Class<*>, position: Int) {
        mRemoteDeviceListAdapter.setSelectedPosition(position)
        Log.d(javaClass.name, "$clazz#$position clicked")
    }

}