package org.akshara.activity;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveId;
import com.google.android.gms.drive.OpenFileActivityBuilder;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;

import org.akshara.BuildConfig;
import org.akshara.R;
import org.akshara.Util.PrefUtil;
import org.akshara.services.FetchPartnerDataService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Show the progress of Partner Data Download
 */
public class DriveSyncActivity extends AppCompatActivity {

    public static final String[] SCOPES = {
            SheetsScopes.DRIVE,
            SheetsScopes.DRIVE_FILE,
            SheetsScopes.SPREADSHEETS,
            SheetsScopes.SPREADSHEETS_READONLY
    };

    public static final String PREF_ACCOUNT_NAME = "accountName";
    public static final String PREF_SHEET_NAME = "sheetName";

    private static final String SHEETS_DATA_RANGE = "district_info!A1:A";

    static final int REQUEST_ACCOUNT_PICKER = 1000;
    static final int REQUEST_AUTHORIZATION = 1001;
    static final int REQUEST_GOOGLE_PLAY_SERVICES = 1002;
    static final int REQUEST_PERMISSION_GET_ACCOUNTS = 1003;
    static final int REQUEST_CODE_FILE_PICKER = 1004;
    static final int REQUEST_CODE_GOOGLE_API_CONNECT_RES = 1005;

    GoogleAccountCredential mCredential;

    GoogleApiClient mGoogleAPIClient;

    String mFileID = FetchPartnerDataService.PARTNER_DATA_FILE_ID;


    private BroadcastReceiver mSyncStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                int errorCode = intent.getIntExtra(
                        FetchPartnerDataService.EXTRA_IS_USER_RECOVERABLE_ERROR, -1);

                switch (errorCode) {
                    case FetchPartnerDataService.ERROR_RECOVERABLE:
                        Intent recoverableErrorIntent = intent
                                .getParcelableExtra(FetchPartnerDataService.EXTRA_RECOVERABLE_INTENT);
                        startActivityForResult(recoverableErrorIntent, REQUEST_AUTHORIZATION);
                        break;

                    case FetchPartnerDataService.ERROR_UN_RECOVERABLE:
                        String error = intent.getStringExtra(FetchPartnerDataService.EXTRA_ERROR_MESSAGE);
                        showErrorMessage(error);
                        break;

                    default:
                        moveToLoginScreen();
                        break;
                }
            }
        }
    };




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drive_sync);

        LocalBroadcastManager.getInstance(this).registerReceiver(mSyncStateReceiver,
                FetchPartnerDataService.getActionIntent());

        // Initialize credentials and service object.
        mCredential = GoogleAccountCredential.usingOAuth2(
                getApplicationContext(), Arrays.asList(SCOPES))
                .setBackOff(new ExponentialBackOff());

        downloadPartnerData();
    }


    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mSyncStateReceiver);
        super.onDestroy();
    }

    /**
     * Attempt to call the API, after verifying that all the preconditions are
     * satisfied. The preconditions are: Google Play Services installed, an
     * account was selected and the device currently has online access. If any
     * of the preconditions are not satisfied, the app will prompt the user as
     * appropriate.
     */
    private void downloadPartnerData() {
        if (! isGooglePlayServicesAvailable()) {
            acquireGooglePlayServices();
        } else if (mCredential.getSelectedAccountName() == null) {
            chooseAccount();
        } else if (! isDeviceOnline()) {
            Toast.makeText(this, "No network connection available.", Toast.LENGTH_SHORT).show();
            moveToLoginScreen();
        } else {
//            displaySheetName();
            callFilePicker();
        }
    }


    private void callFilePicker() {
        if (mGoogleAPIClient == null) {
            mGoogleAPIClient = new GoogleApiClient.Builder(this)
                    .addApi(Drive.API)
                    .addScope(Drive.SCOPE_FILE)
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(@Nullable Bundle bundle) {
                            startFilePicker();
                        }

                        @Override
                        public void onConnectionSuspended(int i) {

                        }
                    })
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                            if (connectionResult.hasResolution()) {
                                try {
                                    connectionResult.startResolutionForResult(
                                            DriveSyncActivity.this,
                                            REQUEST_CODE_GOOGLE_API_CONNECT_RES);
                                } catch (IntentSender.SendIntentException e) {
                                    e.printStackTrace();
                                    showErrorMessage("Not able to connect Google API");
                                }

                            }
                        }
                    })
                    .setAccountName(PrefUtil.getString(DriveSyncActivity.PREF_ACCOUNT_NAME))
                    .build();
        }

        if (mGoogleAPIClient.isConnected()) {
            startFilePicker();
            return;
        }

        mGoogleAPIClient.connect();


    }


    private void startFilePicker() {
        if (!mGoogleAPIClient.isConnected()) {
            return;
        }

        IntentSender intentSender = Drive.DriveApi.newOpenFileActivityBuilder()
                .setMimeType(new String[]{"application/vnd.google-apps.spreadsheet"})
                .build(mGoogleAPIClient);

        try {
            startIntentSenderForResult(intentSender, REQUEST_CODE_FILE_PICKER, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException e) {
            e.printStackTrace();
        }
    }


    private void displaySheetName() {
        HttpTransport transport = AndroidHttp.newCompatibleTransport();
        JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();

        final Sheets mSheetsService = new Sheets.Builder(transport, jsonFactory, mCredential)
                .setApplicationName(BuildConfig.APPLICATION_ID)
                .build();


        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ValueRange valueRange = mSheetsService.spreadsheets().values()
                            .get(mFileID, SHEETS_DATA_RANGE)
                            .execute();


                    if (valueRange != null && valueRange.size() > 0) {
                        List<List<Object>> districtSet = valueRange.getValues();

                        if (districtSet != null && districtSet.size() > 0) {
                            final List<String> district = new ArrayList<>();

                            for (List row : districtSet) {
                                for (Object data : row) {
                                    district.add((String) data);
                                }
                            }


                            Log.i("DriveSyncActivity", "run: " + district);

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    selectDistrict(district);
                                }
                            });
                        }
                    }
                } catch (IOException e) {
                    if (e instanceof UserRecoverableAuthIOException) {
                        Intent recoverableErrorIntent = ((UserRecoverableAuthIOException) e).getIntent();
                        startActivityForResult(recoverableErrorIntent, REQUEST_AUTHORIZATION);
                    }
                }
            }
        }).start();
    }


    private void selectDistrict(List<String> district) {
        final String [] districtArray = district.toArray(new String[]{});

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setItems(districtArray, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(DriveSyncActivity.this,
                                FetchPartnerDataService.class);
                        intent.putExtra(FetchPartnerDataService.EXTRA_DISTRICT, districtArray[which]);
                        startService(intent);
                    }
                })
                .create();

        dialog.show();
    }



    /**
     * Attempts to set the account used with the API credentials. If an account
     * name was previously saved it will use that one; otherwise an account
     * picker dialog will be shown to the user. Note that the setting the
     * account to use with the credentials object requires the app to have the
     * GET_ACCOUNTS permission, which is requested here if it is not already
     * present. The AfterPermissionGranted annotation indicates that this
     * function will be rerun automatically whenever the GET_ACCOUNTS permission
     * is granted.
     */
    private void chooseAccount() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED) {
            String accountName = PrefUtil.getString(PREF_ACCOUNT_NAME);
            if (accountName != null) {
                mCredential.setSelectedAccountName(accountName);
                downloadPartnerData();
            } else {
                // Start a dialog from which the user can choose an account
                startActivityForResult(
                        mCredential.newChooseAccountIntent(),
                        REQUEST_ACCOUNT_PICKER);
            }
        } else {
            // Request the GET_ACCOUNTS permission via a user dialog
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.GET_ACCOUNTS},
                    REQUEST_PERMISSION_GET_ACCOUNTS);
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch(requestCode) {
            case REQUEST_GOOGLE_PLAY_SERVICES:
                if (resultCode != RESULT_OK) {
                    AlertDialog dialog = new AlertDialog.Builder(this)
                            .setMessage( "This app requires Google Play Services. Please install " +
                                    "Google Play Services on your device and relaunch this app.")
                            .create();
                    dialog.show();
                } else {
                    downloadPartnerData();
                }
                break;
            case REQUEST_ACCOUNT_PICKER:
                if (resultCode == RESULT_OK && data != null &&
                        data.getExtras() != null) {
                    String accountName =
                            data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                    if (accountName != null) {
                        PrefUtil.storeString(PREF_ACCOUNT_NAME, accountName);
                        mCredential.setSelectedAccountName(accountName);
                        downloadPartnerData();
                    }
                }
                break;
            case REQUEST_AUTHORIZATION:
                if (resultCode == RESULT_OK) {
                    downloadPartnerData();
                }
                break;

            case REQUEST_CODE_FILE_PICKER:
                if (resultCode == RESULT_OK) {
                    DriveId driveId = data.getParcelableExtra(
                            OpenFileActivityBuilder.EXTRA_RESPONSE_DRIVE_ID);
                    Log.i("DriveSyncActivity", "onActivityResult: "
                            + driveId.getResourceId());

                    mFileID = driveId.getResourceId();

                    Intent fetchPartnerService = new Intent(DriveSyncActivity.this,
                            FetchPartnerDataService.class);
                    fetchPartnerService.putExtra(FetchPartnerDataService.EXTRA_FILE_ID, mFileID);
                    startService(fetchPartnerService);

//                    displaySheetName();
                }
                break;

            case REQUEST_CODE_GOOGLE_API_CONNECT_RES:
                downloadPartnerData();
                break;

        }
    }

    /**
     * Respond to requests for permissions at runtime for API 23 and above.
     * @param requestCode The request code passed in
     *     requestPermissions(android.app.Activity, String, int, String[])
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *     which is either PERMISSION_GRANTED or PERMISSION_DENIED. Never null.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSION_GET_ACCOUNTS) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                chooseAccount();
            }
        }
    }


    /**
     * Checks whether the device currently has a network connection.
     * @return true if the device has a network connection, false otherwise.
     */
    private boolean isDeviceOnline() {
        ConnectivityManager connMgr =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return (networkInfo != null && networkInfo.isConnected());
    }

    /**
     * Check that Google Play services APK is installed and up to date.
     * @return true if Google Play Services is available and up to
     *     date on this device; false otherwise.
     */
    private boolean isGooglePlayServicesAvailable() {
        GoogleApiAvailability apiAvailability =
                GoogleApiAvailability.getInstance();
        final int connectionStatusCode =
                apiAvailability.isGooglePlayServicesAvailable(this);
        return connectionStatusCode == ConnectionResult.SUCCESS;
    }

    /**
     * Attempt to resolve a missing, out-of-date, invalid or disabled Google
     * Play Services installation via a user dialog, if possible.
     */
    private void acquireGooglePlayServices() {
        GoogleApiAvailability apiAvailability =
                GoogleApiAvailability.getInstance();
        final int connectionStatusCode =
                apiAvailability.isGooglePlayServicesAvailable(this);
        if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode);
        }
    }

    private void moveToLoginScreen() {
        Intent syncIntent = new Intent(DriveSyncActivity.this, MainActivity.class);
        startActivity(syncIntent);

        finish();
    }


    void showErrorMessage(String error) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(error)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        moveToLoginScreen();
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .create();

        dialog.show();
    }


    /**
     * Display an error dialog showing that Google Play Services is missing
     * or out of date.
     * @param connectionStatusCode code describing the presence (or lack of)
     *     Google Play Services on this device.
     */
    void showGooglePlayServicesAvailabilityErrorDialog(
            final int connectionStatusCode) {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        Dialog dialog = apiAvailability.getErrorDialog(
                DriveSyncActivity.this,
                connectionStatusCode,
                REQUEST_GOOGLE_PLAY_SERVICES);
        dialog.show();
    }
}
