//
//  Copyright (c) Microsoft Corporation. All rights reserved.
//
package xyz.nfcv.nearshare

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.microsoft.connecteddevices.*
import com.microsoft.connecteddevices.EventListener
import com.microsoft.connecteddevices.remotesystems.*
import com.microsoft.connecteddevices.remotesystems.commanding.RemoteSystemConnectionRequest
import com.microsoft.connecteddevices.remotesystems.commanding.nearshare.NearShareFileProvider
import com.microsoft.connecteddevices.remotesystems.commanding.nearshare.NearShareHelper
import com.microsoft.connecteddevices.remotesystems.commanding.nearshare.NearShareSender
import com.microsoft.connecteddevices.remotesystems.commanding.nearshare.NearShareStatus
import kotlinx.android.synthetic.main.activity_main.*
import java.lang.ref.WeakReference
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.collections.ArrayList

/*
 * Wrapper to hold the ConnectedDevicesAccount and RemoteSystemWatcher,
 * as well as additional information required for NearShare.
 * Verification:
 *      1. Discovery: Launch the NearShare App and select check box "Spatially Proximal"
 *      2. At the point, Platform is initialized and started and users can
 *      see list of spatially proximal devices.
 *      3. Send URI: Select a device from the list and click "Send Uri"
 *      4. You will see a toast on the target device with the uri that users can click and launch.
 *      5. Send File(s): Go to photos app or pick any file(s) and select share, pick NearShare app
 *      6. This will open the NearShareApp, continue to select checkbox "Spatially Proximal"
 *      7. Select the device you want to send the file to and click "Send Files" from app
 *      8. Toast will pop up on the target device with options to accept or decline
 *      9. On clicking accept, file transfer with complete and the users can open\save the file.
 */
class MainActivity : AppCompatActivity(), DeviceRecyclerAdapter.OnItemClickListener {
    private lateinit var mPlatform: ConnectedDevicesPlatform
    private lateinit var mRemoteSystemWatcher: RemoteSystemWatcher
    private lateinit var mRemoteDeviceListAdapter: DeviceRecyclerAdapter
    private lateinit var mNearShareSender: NearShareSender
    private val mFiles: ArrayList<Uri> = arrayListOf()
    private var mWatcherStarted = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        mRemoteDeviceListAdapter = DeviceRecyclerAdapter(applicationContext, this)
        requestPermissions()
        initializePlatform()
        listRemoteSystems.adapter = mRemoteDeviceListAdapter
        listRemoteSystems.layoutManager = LinearLayoutManager(this)
        btnSendUri.setOnClickListener { sendUri() }
        btnSendFile.setOnClickListener { sendFile() }
        btnChooseFileUri.setOnClickListener { chooseFiles() }
        chkProximalDiscovery.setOnClickListener { startOrRestartRemoteSystemWatcher() }
        mNearShareSender = NearShareSender()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        // noinspection SimplifiableIfStatement
        return when (item.itemId) {
            R.id.action_settings -> {
                true
            }

            R.id.action_account_settings -> {
                startActivity(Intent(this@MainActivity, MSAccountActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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

    private fun createAndAddAccount(platform: ConnectedDevicesPlatform) {
        val account = ConnectedDevicesAccount("", ConnectedDevicesAccountType.AAD)
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
        val uriText = txtUri.text.toString()
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

    /**
     * Pick Files and Send using NearShare, helper function to pick files and send.
     */
    private fun setupAndBeginSendFileAsync(): AsyncOperation<NearShareStatus>? {
        var asyncFileTransferOperation: AsyncOperation<NearShareStatus>? = null
        val remoteSystem = mRemoteDeviceListAdapter.mSelected
        if (null != remoteSystem) {
            val remoteSystemConnectionRequest = RemoteSystemConnectionRequest(remoteSystem)
            if (mNearShareSender.isNearShareSupported(remoteSystemConnectionRequest)) {
                val cancellationToken: CancellationToken? = null
                btnCancel.isEnabled = true

                // Call the appropriate api based on the number of files shared to the app.
                if (1 == mFiles.size) {
                    val nearShareFileProvider = NearShareHelper.createNearShareFileFromContentUri(mFiles[0], applicationContext)
                    asyncFileTransferOperation = mNearShareSender.sendFileAsync(remoteSystemConnectionRequest, nearShareFileProvider)
                } else {
                    val nearShareFileProviderArray = arrayOfNulls<NearShareFileProvider>(mFiles.size)
                    for (index in mFiles.indices) {
                        nearShareFileProviderArray[index] = NearShareHelper.createNearShareFileFromContentUri(mFiles[index], applicationContext)
                    }
                    asyncFileTransferOperation = mNearShareSender.sendFilesAsync(remoteSystemConnectionRequest, nearShareFileProviderArray)
                }
            }
        }
        return asyncFileTransferOperation
    }

    /**
     * Send file(s) to the target device using nearshare. Select a photo or file and share to the nearshare app.
     */
    private fun sendFile() {
        val asyncFileTransferOperation = setupAndBeginSendFileAsync()
        val remoteSystem = mRemoteDeviceListAdapter.mSelected

        if (null != remoteSystem) {
            btnCancel.setOnClickListener(object : View.OnClickListener {
                private var mAsyncOperation: AsyncOperation<NearShareStatus>? = null
                fun init(asyncOperation: AsyncOperation<NearShareStatus>?): View.OnClickListener {
                    mAsyncOperation = asyncOperation
                    return this
                }

                /**
                 * Called when a view has been clicked.
                 *
                 * @param v The view that was clicked.
                 */
                override fun onClick(v: View) {
                    mAsyncOperation?.cancel(true)
                }
            }.init(asyncFileTransferOperation))
            asyncFileTransferOperation?.whenCompleteAsync { nearShareStatus, throwable ->
                btnCancel.isEnabled = false
                if (null != throwable) {
                    LOG.log(Level.SEVERE, String.format("Exception during file transfer: %1\$s", throwable.message))
                } else {
                    if (nearShareStatus == NearShareStatus.COMPLETED) {
                        LOG.log(Level.INFO, "File transfer completed")
                    } else {
                        LOG.log(Level.SEVERE, "File transfer failed")
                    }
                }
            }
        }
    }

    private fun chooseFiles() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).apply { type = "*/*" }
        startActivityForResult(intent, PICK_FILE_S)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            PICK_FILE_S -> data?.data?.also { uri ->
                mFiles.clear()
                mFiles.add(uri)
                btnChooseFileUri.text = uri.path?.split("/")?.last()
            }
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
        const val PICK_FILE_S = 2
    }

    override fun onItemClick(clazz: Class<*>, position: Int) {
        mRemoteDeviceListAdapter.setSelectedPosition(position)
        Log.d(javaClass.name, "$clazz#$position clicked")
    }
}