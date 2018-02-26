package android.fitget

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.AsyncTask
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.drive.Drive
import com.google.android.gms.drive.DriveClient
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.MetadataBuffer
import com.google.android.gms.drive.query.Filters
import com.google.android.gms.drive.query.Query
import com.google.android.gms.drive.query.SearchableField
import com.google.android.gms.tasks.Task
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.SheetsScopes.SPREADSHEETS_READONLY
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import java.io.IOException

class MainActivity : AppCompatActivity(), EasyPermissions.PermissionCallbacks {
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_AUTHORIZATION = 1001
        private const val REQUEST_GOOGLE_PLAY_SERVICES = 1002
        private const val REQUEST_PERMISSION_GET_ACCOUNTS = 1003
        private const val REQUEST_CODE_SIGN_IN = 1004

        private const val PREF_ACCOUNT_NAME = "accountName"
        private val DRIVE_SCOPES: HashSet<Scope> = HashSet()
        private val SHEET_SCOPES: HashSet<String> = HashSet()
    }

    init {
        SHEET_SCOPES.add(SPREADSHEETS_READONLY)
        SHEET_SCOPES.add(SheetsScopes.SPREADSHEETS)
        DRIVE_SCOPES.add(Drive.SCOPE_FILE)
        DRIVE_SCOPES.add(Drive.SCOPE_APPFOLDER)
    }

    // Drive API clients
    private var mCredential: GoogleAccountCredential? = null
    private var mDriveClient: DriveClient? = null
    private var mDriveResourceClient: DriveResourceClient? = null

    // Layout
    private var mAdapter: RecyclerView.Adapter<*>? = null
    private var mLayoutManager: RecyclerView.LayoutManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG).setAction("Action", null).show()
        }
        btn_call_api.setOnClickListener {
            getResultsFromApi()
        }
        pb_api_progress.visibility = View.INVISIBLE

        mLayoutManager = LinearLayoutManager(this)
        val planA = Plan("Plan A")
        val planB = Plan("Plan B")
        val planC = Plan("Plan C")
        val planD = Plan("Plan D")
        val planE = Plan("Plan E")
        val planF = Plan("Plan F")
        val data = arrayListOf(planA, planB, planC, planD, planE, planF)
        mAdapter = PlansAdapter(data)

        rv_plans.setHasFixedSize(true)
        rv_plans.layoutManager = mLayoutManager
        rv_plans.adapter = mAdapter

        // Initialize credentials and service object.
        mCredential = GoogleAccountCredential.usingOAuth2(applicationContext, SHEET_SCOPES.toList()).setBackOff(ExponentialBackOff())
    }

    /**
     * Attempt to call the API, after verifying that all the preconditions are
     * satisfied. The preconditions are: Google Play Services installed, an
     * account was selected and the device currently has online access. If any
     * of the preconditions are not satisfied, the app will prompt the user as
     * appropriate.
     */
    private fun getResultsFromApi() {
        Log.d(TAG, "getResultsFromApi")
        if (!ensurePermissionGranted()) {
            return
        }
        if (!isGooglePlayServicesAvailable()) {
            acquireGooglePlayServices()
        } else if (mDriveClient == null || mDriveResourceClient == null) {
            signIn()
        } else if (!isDeviceOnline()) {
            tv_api_response.text = getString(R.string.err_no_network)
        } else {
            findFiles()
            MakeRequestTask(mCredential!!).execute()
        }
    }

    private fun signIn() {
        Log.d(TAG, "signIn")
        val signInAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (signInAccount != null && signInAccount.grantedScopes.containsAll(DRIVE_SCOPES)) {
            initializeDriveClient(signInAccount)
        } else {
            val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestScopes(Drive.SCOPE_FILE)
                    .requestScopes(Drive.SCOPE_APPFOLDER)
                    .requestEmail()
                    .requestProfile()
                    .build()
            val googleSignInClient = GoogleSignIn.getClient(this, signInOptions)
            startActivityForResult(googleSignInClient.signInIntent, REQUEST_CODE_SIGN_IN)
        }
    }

    /**
     * Setting the account to use with the credentials object requires the app to have the
     * GET_ACCOUNTS permission, which is requested here if it is not already
     * present. The AfterPermissionGranted annotation indicates that this
     * function will be rerun automatically whenever the GET_ACCOUNTS permission
     * is granted.
     */
    @AfterPermissionGranted(REQUEST_PERMISSION_GET_ACCOUNTS)
    private fun ensurePermissionGranted(): Boolean {
        Log.d(TAG, "ensurePermissionGranted")
        if (!EasyPermissions.hasPermissions(this, Manifest.permission.GET_ACCOUNTS)) {
            // Request the GET_ACCOUNTS permission via a user dialog
            EasyPermissions.requestPermissions(
                    this,
                    "This app needs to access your Google account (via Contacts).",
                    REQUEST_PERMISSION_GET_ACCOUNTS,
                    Manifest.permission.GET_ACCOUNTS)
            return false
        }
        return true
    }

    /**
     * Called when an activity launched here (specifically, AccountPicker
     * and authorization) exits, giving you the requestCode you started it with,
     * the resultCode it returned, and any additional data from it.
     * @param requestCode code indicating which activity result is incoming.
     * @param resultCode code indicating the result of the incoming activity result.
     * @param data Intent (containing result data) returned by incoming
     * activity result.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "onActivityResult")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_GOOGLE_PLAY_SERVICES -> if (resultCode != Activity.RESULT_OK) {
                tv_api_response.text = getString(R.string.err_no_play_service_installed)
            } else {
                getResultsFromApi()
            }
            REQUEST_AUTHORIZATION -> if (resultCode == Activity.RESULT_OK) {
                // App is authorized, you can go back to sending the API request
                getResultsFromApi()
            }
            REQUEST_CODE_SIGN_IN -> if (resultCode == Activity.RESULT_OK) {
                val getAccountTask: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
                if (getAccountTask.isSuccessful) {
                    Log.d(TAG, "getAccountTask was successful")
                    initializeDriveClient(getAccountTask.result)
                } else {
                    // Login failed, try again
                    Log.d(TAG, "getAccountTask failed")
                    signIn()
                }
            }
            else {
                // User denied access, show him the account chooser again
                getResultsFromApi()
            }
        }
    }

    private fun initializeDriveClient(signInAccount: GoogleSignInAccount) {
        Log.d(TAG, "initializeDriveClient")
        val accountName = signInAccount.account!!.name
        if (accountName != null) {
            val settings = getPreferences(Context.MODE_PRIVATE)
            val editor = settings.edit()
            editor.putString(PREF_ACCOUNT_NAME, accountName)
            editor.apply()
            mCredential!!.selectedAccountName = accountName
            Log.d(TAG, "account name is " + accountName)
        }

        mDriveClient = Drive.getDriveClient(this, signInAccount)
        mDriveResourceClient = Drive.getDriveResourceClient(this, signInAccount)
        getResultsFromApi()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Respond to requests for permissions at runtime for API 23 and above.
     * @param requestCode The request code passed in
     * requestPermissions(android.app.Activity, String, int, String[])
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     * which is either PERMISSION_GRANTED or PERMISSION_DENIED. Never null.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        Log.d(TAG, "onRequestPermissionsResult")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)

    }

    /**
     * Callback for when a permission is granted using the EasyPermission library.
     * @param requestCode The request code associated with the requested permission
     * @param list The requested permission list. Never null.
     */
    override fun onPermissionsGranted(requestCode: Int, list: List<String>) {
        Log.d(TAG, "onPermissionsGranted")
        getResultsFromApi()
    }

    /**
     * Callback for when a permission is denied using the EasyPermissions library permission
     * @param list The requested permission list. Never null.
     */
    override fun onPermissionsDenied(requestCode: Int, list: List<String>) {
        Log.d(TAG, "onPermissionsDenied")
        getResultsFromApi()
    }

    /**
     * Checks whether the device currently has a network connection.
     * @return true if the device has a network connection, false otherwise.
     */
    private fun isDeviceOnline(): Boolean {
        Log.d(TAG, "isDeviceOnline")
        val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connMgr.activeNetworkInfo
        return networkInfo != null && networkInfo.isConnected
    }

    /**
     * Check that Google Play services APK is installed and up to date.
     * @return true if Google Play Services is available and up to
     * date on this device; false otherwise.
     */
    private fun isGooglePlayServicesAvailable(): Boolean {
        Log.d(TAG, "isGooglePlayServicesAvailable")
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(this)
        return connectionStatusCode == ConnectionResult.SUCCESS
    }

    /**
     * Attempt to resolve a missing, out-of-date, invalid or disabled Google
     * Play Services installation via a user dialog, if possible.
     */
    private fun acquireGooglePlayServices() {
        Log.d(TAG, "acquireGooglePlayServices")
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(this)
        if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode)
        }
    }

    /**
     * Display an error dialog showing that Google Play Services is missing
     * or out of date.
     * @param connectionStatusCode code describing the presence (or lack of)
     * Google Play Services on this device.
     */
    fun showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode: Int) {
        Log.d(TAG, "showGooglePlayServicesAvailabilityErrorDialog")
        val apiAvailability = GoogleApiAvailability.getInstance()
        val dialog = apiAvailability.getErrorDialog(
                this@MainActivity,
                connectionStatusCode,
                REQUEST_GOOGLE_PLAY_SERVICES)
        dialog.show()
    }

    /* TODO put into separate AsyncTask */
    private fun findFiles() {
        Log.d(TAG, "findFiles")
        val mQuery = Query.Builder().addFilter(Filters.contains(SearchableField.TITLE, "Trainingsplan")).build()

        val queryTask: Task<MetadataBuffer> = this@MainActivity.mDriveResourceClient!!.query(mQuery!!)
        queryTask.addOnSuccessListener {
            Log.d(TAG, "success")
            for (metadata in it) {
                Log.d(TAG, metadata.originalFilename)
                Log.d(TAG, metadata.title)
                Log.d(TAG, metadata.mimeType)
                Log.d(TAG, metadata.driveId.toString())
            }
            it.release()
        }
        queryTask.addOnFailureListener {
            Log.d(TAG, "failure " + it.message)
        }
    }

    /**
     * An asynchronous task that handles the Google Sheets API call.
     * Placing the API calls in their own task ensures the UI stays responsive.
     */
    /* TODO put into separate file */
    @SuppressLint("StaticFieldLeak")
    inner class MakeRequestTask(credential: GoogleAccountCredential): AsyncTask<Void, Void, List<String>>() {

        private var mService: Sheets? = null
        private var mLastError: Exception? = null

        init {
            val transport: HttpTransport = AndroidHttp.newCompatibleTransport()
            val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()
            this.mService = Sheets.Builder(transport, jsonFactory, credential).setApplicationName(getString(R.string.app_name)).build()
        }

        override fun onPreExecute() {
            Log.d(TAG, "onPreExecute")
            super.onPreExecute()
            this@MainActivity.pb_api_progress.visibility = View.VISIBLE
        }

        override fun onPostExecute(result: List<String>?) {
            Log.d(TAG, "onPostExecute")
            super.onPostExecute(result)
            this@MainActivity.pb_api_progress.visibility = View.INVISIBLE
            if (result == null || result.isEmpty()) {
                this@MainActivity.tv_api_response.text = "No results returned"
            } else {
                this@MainActivity.tv_api_response.text = TextUtils.join("\n", result)
            }
        }

        override fun onCancelled() {
            Log.d(TAG, "onCancelled")
            super.onCancelled()
            this@MainActivity.pb_api_progress.visibility = View.INVISIBLE
            if (mLastError != null) {
                when (mLastError) {
                    is GooglePlayServicesAvailabilityIOException
                    -> this@MainActivity.showGooglePlayServicesAvailabilityErrorDialog((mLastError as GooglePlayServicesAvailabilityIOException).connectionStatusCode)
                    is UserRecoverableAuthIOException
                    -> startActivityForResult((mLastError as UserRecoverableAuthIOException).intent, MainActivity.REQUEST_AUTHORIZATION)
                    else
                    -> this@MainActivity.tv_api_response.text = getString(R.string.err_generic, mLastError!!.message)
                }
            } else {
                this@MainActivity.tv_api_response.text = getString(R.string.err_generic, "Request has been cancelled")
            }
        }

        /**
         * Background task to call Google Sheets API.
         * @param p0 no parameters needed for this task.
         */
        override fun doInBackground(vararg p0: Void?): List<String>? {
            Log.d(TAG, "doInBackground")
            return try {
                getDataFromApi()
            } catch (e: Exception) {
                mLastError = e
                Log.e(TAG, e.message)
                cancel(true)
                null
            }
        }

        /**
         * Fetch a list of names and majors of students in a sample spreadsheet:
         * https://docs.google.com/spreadsheets/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms/edit
         * @return List of names and majors
         * @throws IOException
         */
        private fun getDataFromApi(): List<String> {
            Log.d(TAG, "getDataFromApi")
            val spreadsheetId = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
            val range = "Class Data!A2:E"
            val results: MutableList<String> = mutableListOf()

            val response: ValueRange? = this.mService!!.spreadsheets().values().get(spreadsheetId, range).execute()
            val values: List<List<Any>> = response!!.getValues()
            results.add("Name, Major")
            values.mapTo(results) { it[0].toString() + ", " + it[1] }
            return results
        }
    }
}