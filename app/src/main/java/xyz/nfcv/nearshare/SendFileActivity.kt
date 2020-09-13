package xyz.nfcv.nearshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import kotlinx.android.synthetic.main.activity_send_file.*
import java.lang.ref.WeakReference
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.collections.ArrayList

class SendFileActivity : AppCompatActivity(), DeviceRecyclerAdapter.OnItemClickListener, FileRecyclerAdapter.OnItemClickListener {
    private lateinit var mPlatform: ConnectedDevicesPlatform
    private lateinit var mRemoteSystemWatcher: RemoteSystemWatcher
    private lateinit var mRemoteDeviceListAdapter: DeviceRecyclerAdapter
    private lateinit var mNearShareSender: NearShareSender
    private var mWatcherStarted = false

    private lateinit var mFileRecyclerAdapter: FileRecyclerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_file)
        setSupportActionBar(toolbar)

        mFileRecyclerAdapter = FileRecyclerAdapter(applicationContext, this)
        mRemoteDeviceListAdapter = DeviceRecyclerAdapter(applicationContext, this)
        listSelectedFiles.adapter = mFileRecyclerAdapter
        listSelectedFiles.layoutManager = LinearLayoutManager(this)
        listRemoteSystems.adapter = mRemoteDeviceListAdapter
        listRemoteSystems.layoutManager = LinearLayoutManager(this)
        initFiles()
        requestPermissions()
        initializePlatform()

        mNearShareSender = NearShareSender()
        chkProximalDiscovery.setOnClickListener { startOrRestartRemoteSystemWatcher() }
        btnSendFile.setOnClickListener { sendFile() }
    }

    /**
     * Helper Function to initialize the files based on whether user is trying to share
     * single file or multiple files.
     */
    private fun initFiles() {
        val launchIntent = intent
        when (launchIntent.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = launchIntent.getParcelableExtra(Intent.EXTRA_STREAM)
                uri?.let { mFileRecyclerAdapter.addFile(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val files: ArrayList<Uri>? = launchIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                files?.let { mFileRecyclerAdapter.addAllFile(it.toList()) }
            }
        }
    }

    companion object {
        private val LOG = Logger.getLogger(SendFileActivity::class.java.simpleName)
    }

    override fun onItemClick(clazz: Class<*>, position: Int) {
        Log.d(javaClass.name, "$clazz#$position clicked")

        when (clazz) {
            FileRecyclerAdapter.ViewHolder::class.java -> mFileRecyclerAdapter.setSelectedPosition(position)
            DeviceRecyclerAdapter.ViewHolder::class.java -> mRemoteDeviceListAdapter.setSelectedPosition(position)
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
     * Pick Files and Send using NearShare, helper function to pick files and send.
     */
    private fun setupAndBeginSendFileAsync(): AsyncOperation<NearShareStatus>? {
        val mFiles = mFileRecyclerAdapter.mSelected
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

}