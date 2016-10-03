/**
 * Copyright (C) 2011-2015  Dinko Korunic <dinko.korunic@gmail.com>
 * <p/>
 * This work is licensed under the Creative Commons
 * Attribution-NonCommercial-NoDerivs 3.0 Unported License. To view a copy of
 * this license, visit http://creativecommons.org/licenses/by-nc-nd/3.0/ or
 * send a letter to Creative Commons, 444 Castro Street, Suite 900, Mountain
 * View, California, 94042, USA.
 * <p/>
 * More information:
 * http://creativecommons.org/licenses/by-nc-nd/3.0/
 * http://creativecommons.org/licenses/by-nc-nd/3.0/legalcode
 */

package net.dkorunic.hznet;

import android.Manifest;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AlertDialog.Builder;
import android.support.v7.app.AppCompatActivity;
import android.text.SpannableString;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import com.crashlytics.android.Crashlytics;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import au.com.bytecode.opencsv.CSVReader;


public class HZnetMapFragment extends AppCompatActivity implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener, LocationListener, OnMapReadyCallback {
    private static final char CSV_DELIMITER = ';'; //$NON-NLS-1$
    private static final String HZNET_CHARSET = "windows-1250"; //$NON-NLS-1$
    private final static int CONNECTION_FAILURE_RESOLUTION_REQUEST = 9000;
    private GoogleMap googleMap;
    private AsyncTaskLoader mAsyncTaskLoader;
    private boolean mFirstFix;
    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;
    private boolean mResolvingError = false;

    private void setupMap() {
        if (null == googleMap) {
            ((SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map)).getMapAsync(this);
        } else {
            //noinspection ResourceType
            googleMap.setMyLocationEnabled(true);
        }
    }

    @Override
    public void onMapReady(final GoogleMap map) {
        googleMap = map;

        if (googleMap != null) {
            googleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);

            //noinspection ResourceType
            googleMap.setMyLocationEnabled(true);

            googleMap.getUiSettings().setCompassEnabled(true);
            googleMap.getUiSettings().setZoomControlsEnabled(true);
            googleMap.getUiSettings().setZoomGesturesEnabled(true);
            googleMap.getUiSettings().setMyLocationButtonEnabled(true);
            googleMap.getUiSettings().setRotateGesturesEnabled(true);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.mapview);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setIcon(R.drawable.icon);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setSubtitle(R.string.prikaz_karta);
        }

        // location manager, location provider
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

        // Create the LocationRequest object
        mLocationRequest = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(10 * 1000)        // 10 seconds
                .setFastestInterval(1000); // 1 second

        // check if GMS is present at all
        int status = GooglePlayServicesUtil.isGooglePlayServicesAvailable(getApplicationContext());
        if (ConnectionResult.SUCCESS != status) {
            try {
                Dialog d = GooglePlayServicesUtil.getErrorDialog(status, this, status);
                if (d != null) {
                    d.show();
                }
            } catch (ActivityNotFoundException e) {
                Crashlytics.getInstance().core.logException(e);
            }
        } else {
            // setup map
            setupMap();

            // async task for loading and showing markers
            mAsyncTaskLoader = new AsyncTaskLoader();
            mAsyncTaskLoader.execute();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();

        setupMap();

        mGoogleApiClient.connect();

    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mGoogleApiClient.isConnected()) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);
            }
            mGoogleApiClient.disconnect();
        }
    }

    @Override
    public void onDestroy() {
        if (mAsyncTaskLoader != null) {
            mAsyncTaskLoader.cancel(true);
        }

        super.onDestroy();
    }

    private void handleNewLocation(Location location) {
        double currentLatitude = location.getLatitude();
        double currentLongitude = location.getLongitude();

        LatLng latLng = new LatLng(currentLatitude, currentLongitude);

        // zoomiraj lokaciju samo prvi put
        if (googleMap != null && !mFirstFix) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14));
            mFirstFix = true;
        }

    }

    @Override
    public void onConnected(Bundle bundle) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            Location location = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);

            if (null == location) {
                LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, mLocationRequest, this);
            } else {
                handleNewLocation(location);
            }
        }
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        if (mResolvingError) {
            // do nothing
        } else if (connectionResult.hasResolution()) {
            try {
                mResolvingError = true;
                connectionResult.startResolutionForResult(this, CONNECTION_FAILURE_RESOLUTION_REQUEST);
            } catch (IntentSender.SendIntentException e) {
                mGoogleApiClient.connect();
            }
        } else {
            GooglePlayServicesUtil.getErrorDialog(connectionResult.getErrorCode(), this, 0).show();
            mResolvingError = true;
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        handleNewLocation(location);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.menu_web_parse, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                // TODO: povratak na prethodni Activity?
                Intent homeIntent = new Intent(this, HZnet.class);
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(homeIntent);
                return true;
            case R.id.about:
                final SpannableString mContent = new SpannableString(getString(R.string.about));
                Linkify.addLinks(mContent, Linkify.EMAIL_ADDRESSES | Linkify.WEB_URLS
                        | Linkify.MAP_ADDRESSES);
                TextView aboutTextView = new TextView(HZnetMapFragment.this);
                aboutTextView.setPadding(10, 10, 10, 10);
                aboutTextView.setGravity(Gravity.CENTER_HORIZONTAL);
                aboutTextView.setText(mContent);
                Builder builderAbout = new AlertDialog.Builder(this);
                builderAbout
                        .setTitle(R.string.about_naziv)
                        .setIcon(R.drawable.icon)
                        .setView(aboutTextView)
                        .setCancelable(true)
                        .setNegativeButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                            }
                        });
                AlertDialog dialogAbout = builderAbout.create();
                dialogAbout.show();
                break;
            case R.id.quit:
                Builder builderQuit = new AlertDialog.Builder(this);
                builderQuit
                        .setMessage(R.string.kraj_rada)
                        .setCancelable(true)
                        .setPositiveButton(R.string.odaberi, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                if (null == getParent()) {
                                    setResult(RESULT_FIRST_USER);
                                } else {
                                    getParent().setResult(RESULT_FIRST_USER);
                                }
                                HZnetMapFragment.this.finish();
                            }
                        })
                        .setNegativeButton(R.string.odustani,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                    }
                                });
                AlertDialog dialogQuit = builderQuit.create();
                dialogQuit.show();
                break;
            default:
                break;
        }
        return true;
    }

    class AsyncTaskLoader extends AsyncTask<Void, Void, Void> {
        private BitmapDescriptor icon;
        private CSVReader geostationReader;
        private List<MarkerOptions> markersList;

        protected void onPreExecute() {
            icon = BitmapDescriptorFactory.fromResource(R.drawable.train_transportation);
            markersList = new ArrayList<>();
        }

        protected Void doInBackground(Void... args) {
            try {
                geostationReader = new CSVReader(
                        new BufferedReader(new InputStreamReader(HZnetMapFragment.this.getResources()
                                .openRawResource(R.raw.stanice), HZNET_CHARSET)), CSV_DELIMITER);
                String[] nextLine;

                // priprema geo stabla
                while (null != (nextLine = geostationReader.readNext())) {
                    if (isCancelled()) {
                        break;
                    }

                    try {
                        LatLng point = new LatLng(Double.parseDouble(nextLine[1]), Double.parseDouble(nextLine[2]));
                        MarkerOptions markerOptions = new MarkerOptions().icon(icon)
                                .title(nextLine[0])
                                .snippet(nextLine[1] + "," + nextLine[2])
                                .position(point);
                        markersList.add(markerOptions);
                    } catch (NumberFormatException e) {
                        Crashlytics.getInstance().core.logException(e);
                    }
                }
            } catch (IOException e) {
                Crashlytics.getInstance().core.logException(e);
            } finally {
                if (null != geostationReader) {
                    try {
                        geostationReader.close();
                    } catch (IOException ignored) {
                    }
                }
            }

            return null;
        }

        protected void onPostExecute(Void result) {
            if (isCancelled()) {
                return;
            }

            // dodavanje ostalih markera po karti
            try {
                if (markersList != null) {
                    for (MarkerOptions marker : markersList) {
                        googleMap.addMarker(marker);
                    }
                }
            } catch (Exception e) {
                Crashlytics.getInstance().core.logException(e);
            }
            markersList = null;
        }
    }
}
