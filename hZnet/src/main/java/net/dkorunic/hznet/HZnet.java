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
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AlertDialog.Builder;
import android.support.v7.app.AppCompatActivity;
import android.text.SpannableString;
import android.text.format.DateUtils;
import android.text.util.Linkify;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.bbn.openmap.util.quadtree.QuadTree;
import com.crashlytics.android.Crashlytics;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.gson.Gson;
import com.googlecode.android.widgets.DateSlider.DateSlider;
import com.googlecode.android.widgets.DateSlider.DateTimeSlider;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import au.com.bytecode.opencsv.CSVReader;
import io.fabric.sdk.android.Fabric;


public class HZnet extends AppCompatActivity implements Runnable, OnClickListener,
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
        LocationListener {
    private static final String TAG = HZnet.class.getSimpleName();
    private static final String HZNET_VRED_START_URL = "http://vred.hzinfra.hr/hzinfo/Default.asp?"; //$NON-NLS-1$
    private static final String HZNET_VRED_END_URL = "Category=hzinfo&Service=vred3&LANG=hr&SCREEN=2"; //$NON-NLS-1$
    private static final String HZNET_DELIMITER = "&"; //$NON-NLS-1$
    private static final String HZNET_CHARSET = "windows-1250"; //$NON-NLS-1$
    private static final int MAX_CACHE_DAYS = 2;
    private static final int INITIAL_SB_CAPACITY = 10;
    private static final char CSV_DELIMITER = ';'; //$NON-NLS-1$
    private static final String DEFAULT_HR_DATE_FORMAT = "%td.%tm.%tY u %tH sati"; //$NON-NLS-1$
    private static final Locale HR_LOCALE = new Locale("hr");
    private static final int MAX_MRU_LIST = 8;
    private static final String MRU_DELIMITER = "\u2192";
    private static final int MAX_DRAWER_OPEN = 5;
    private final static int CONNECTION_FAILURE_RESOLUTION_REQUEST = 9000;
    private static final int REQUEST_CODE_LOCATION = 2;
    private final HHandler handler = new HHandler(this);
    private Calendar mTravelDay;
    private List<String> polazniList;
    private List<String> dolazniList;
    private List<String> viaList;
    private AutoCompleteTextView autoCompleteTextViewNKOD1;
    private AutoCompleteTextView autoCompleteTextViewNKDO1;
    private AutoCompleteTextView autoCompleteTextViewK1;
    private EditText editTextODH1;
    private String stanicaNKOD1;
    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;
    private boolean isBackPressed = false;
    private List<String> mMruList;
    private DrawerLayout mDrawerLayout;
    private ListView mDrawerList;
    private ActionBarDrawerToggle mDrawerToggle;
    private int mDrawerCounter;
    private Location mLocation;
    private boolean mLocationDenied = false;
    private boolean mResolvingError = false;

    private String md5Hash(final String s) {
        MessageDigest mDigest;
        String hash = null;

        try {
            mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(s.getBytes(), 0, s.length());
            hash = new BigInteger(1, mDigest.digest()).toString(16);
        } catch (NoSuchAlgorithmException e) {
            Crashlytics.getInstance().core.log(Log.ERROR, TAG, "Error while trying to use MD5");
        }
        return hash;
    }

    private void handleNewLocation(Location location) {
        mLocation = location;
    }

    private String getStationFromLocation() {
        double myCurrentLat;
        double myCurrentLon;

        // imamo zadnju tocnu lokaciju
        if (null != mLocation) {
            myCurrentLat = mLocation.getLatitude();
            myCurrentLon = mLocation.getLongitude();
        } else {
            // dohvat zadnje aproksimativne lokacije
            Location lastKnownLocation = null;

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                lastKnownLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
            }

            // handle locations
            if (null != lastKnownLocation) {
                Crashlytics.getInstance().core.log(Log.DEBUG, TAG, "Found last known location: " + lastKnownLocation);
            } else {
                Crashlytics.getInstance().core.log(Log.ERROR, TAG, "Cannot get last known location from system");
                return null;
            }

            // imamo zadnju poznatu "otprilicnu" lokaciju
            myCurrentLat = lastKnownLocation.getLatitude();
            myCurrentLon = lastKnownLocation.getLongitude();
        }

        // parsiranje stanica i njihovih lokacija
        CSVReader geostation_reader = null;
        QuadTree myGeoTree = new QuadTree();
        try {
            geostation_reader = new CSVReader(new BufferedReader(new InputStreamReader(HZnet.this
                    .getResources().openRawResource(R.raw.stanice), HZNET_CHARSET)), CSV_DELIMITER);
            String[] nextLine;

            // priprema geo stabla
            while (null != (nextLine = geostation_reader.readNext())) {
                try {
                    myGeoTree.put(Float.parseFloat(nextLine[1]), Float.parseFloat(nextLine[2]),
                            nextLine[0]);
                } catch (NumberFormatException e) {
                    Crashlytics.getInstance().core.logException(e);
                }
            }
        } catch (IOException e) {
            Crashlytics.getInstance().core.logException(e);
        } finally {
            if (null != geostation_reader) {
                try {
                    geostation_reader.close();
                } catch (IOException ignored) {
                }
            }
        }

        // vrati najblizu lokaciju
        return (String) myGeoTree.get((float) myCurrentLat, (float) myCurrentLon);
    }

    public void run() {
        // ciscenje starih cacheva prije svega
        clearCacheFolder(getCacheDir(), MAX_CACHE_DAYS);
        Crashlytics.getInstance().core.log(Log.DEBUG, TAG, "Cleaned old caches");

        // ucitavanje liste polaznih/odredisnih stanica iz datoteke
        CSVReader station_reader = null;
        try {
            station_reader = new CSVReader(new BufferedReader(new InputStreamReader(HZnet.this
                    .getResources().openRawResource(R.raw.sluzbene_stanice), HZNET_CHARSET)),
                    CSV_DELIMITER);
            String[] nextLine;
            polazniList = new ArrayList<>();

            while (null != (nextLine = station_reader.readNext())) {
                polazniList.add(nextLine[0]);
            }
        } catch (IOException e) {
            Crashlytics.getInstance().core.logException(e);
        } finally {
            if (null != station_reader) {
                try {
                    station_reader.close();
                } catch (IOException ignored) {
                }
            }
        }

        // trenutno su na HZ Webu dolazna i polazna lista identicne
        dolazniList = polazniList;

        // ucitavanje liste via stanica iz datoteke
        CSVReader via_reader = null;
        try {
            via_reader = new CSVReader(new BufferedReader(new InputStreamReader(HZnet.this
                    .getResources().openRawResource(R.raw.sluzbene_stanice_via), HZNET_CHARSET)),
                    CSV_DELIMITER);
            String[] nextLine;
            viaList = new ArrayList<>();

            while (null != (nextLine = via_reader.readNext())) {
                viaList.add(nextLine[0]);
            }
        } catch (IOException e) {
            Crashlytics.getInstance().core.logException(e);
        } finally {
            if (null != via_reader) {
                try {
                    via_reader.close();
                } catch (IOException ignored) {
                }
            }
        }

        // dohvat trenutne lokacije
        stanicaNKOD1 = getStationFromLocation();

        // thread je zavrsio s poslom
        handler.sendEmptyMessage(0);
    }

    private void showErrorAndExit(final int resId) {
        Builder dialogErrorAndExit = new AlertDialog.Builder(this);
        dialogErrorAndExit.setMessage(getString(resId)).setCancelable(false)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        //HZnet.this.finish();
                    }
                });
        AlertDialog dialogQuit = dialogErrorAndExit.create();
        try {
            dialogQuit.show();
        } catch (Exception e) {
            // catch FC in case that the app has already went away
        }
    }

    private String getHznetSchedUrl(int typeId) {
        StringBuilder sb = new StringBuilder(INITIAL_SB_CAPACITY);
        sb.append(HZNET_VRED_START_URL);

        // polazni kolodvor (NKOD)
        sb.append("NKOD1=");
        try {
            sb.append(URLEncoder.encode(autoCompleteTextViewNKOD1.getText().toString().trim(),
                    HZNET_CHARSET));
        } catch (UnsupportedEncodingException e) {
            sb.append(autoCompleteTextViewNKOD1.getText().toString());
        }
        sb.append(HZNET_DELIMITER);

        // vrijeme polaska (ODH)
        sb.append("ODH=");
        sb.append(String.format(HR_LOCALE, "%tH", mTravelDay));
        sb.append(HZNET_DELIMITER);

        // odredisni kolodvor (NKDO)
        sb.append("NKDO1=");
        try {
            sb.append(URLEncoder.encode(autoCompleteTextViewNKDO1.getText().toString().trim(),
                    HZNET_CHARSET));
        } catch (UnsupportedEncodingException e) {
            sb.append(autoCompleteTextViewNKDO1.getText().toString());
        }
        sb.append(HZNET_DELIMITER);

        // via kolodvor (K1)
        // via kolodvor (K2) -- ne koristi se
        sb.append("K1=");
        try {
            sb.append(URLEncoder.encode(autoCompleteTextViewK1.getText().toString().trim(),
                    HZNET_CHARSET));
        } catch (UnsupportedEncodingException e) {
            sb.append(autoCompleteTextViewK1.getText().toString());
        }
        sb.append(HZNET_DELIMITER);

        // datum putovanja (DT)
        sb.append("DT=");
        sb.append(String.format(HR_LOCALE, "%td.%tm.%ty", mTravelDay, mTravelDay, mTravelDay));
        sb.append(HZNET_DELIMITER);

        // tip putovanja (direktni, itd.) (DV)
        switch (typeId) {
            case R.id.prikaziA:
                sb.append("DV=A");
                break;
            case R.id.prikaziD:
                sb.append("DV=D");
                break;
            case R.id.prikaziS:
                sb.append("DV=S");
                break;
            default:
                sb.append("DV=D");
                break;
        }
        sb.append(HZNET_DELIMITER);

        // kraj
        sb.append(HZNET_VRED_END_URL);

        Crashlytics.getInstance().core.log(Log.DEBUG, TAG, "Fetching schedule from <URL:" + sb.toString() + ">");
        return sb.toString();
    }

    private String getHznetSchedMD5Url(String url) {
        String hznetSchedMD5URL = md5Hash(url);
        Crashlytics.getInstance().core.log(Log.DEBUG, TAG, "Calculated daily schedule cache ID " + hznetSchedMD5URL);
        return hznetSchedMD5URL;
    }

    private int clearCacheFolder(final File cacheDir, final int numDays) {
        int deletedFiles = 0;

        if (null != cacheDir && cacheDir.isDirectory()) {
            try {
                // listFiles() can return null
                File[] fileList = cacheDir.listFiles();
                if (null == fileList)
                    return 0;

                for (File child : fileList) {
                    // first delete subdirectories recursively
                    if (child.isDirectory()) {
                        deletedFiles += clearCacheFolder(child, numDays);
                    }

                    // then delete the files and subdirectories in this dir
                    // only empty directories can be deleted, so subdirs have
                    // been done first
                    if (child.lastModified() < new Date().getTime() - numDays
                            * DateUtils.DAY_IN_MILLIS) {
                        if (child.delete()) {
                            deletedFiles++;
                        }
                    }
                }
            } catch (Exception e) {
                Crashlytics.getInstance().core.logException(e);
            }
        }
        return deletedFiles;
    }

    private void readPrefs() {
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);

        autoCompleteTextViewNKOD1.setText(preferences.getString("autoCompleteNKOD1", null));
        autoCompleteTextViewNKDO1.setText(preferences.getString("autoCompleteNKDO1", null));
        autoCompleteTextViewK1.setText(preferences.getString("autoCompleteK1", null));
        String mruString = preferences.getString("mruList", null);

        if (mruString != null) {
            try {
                //noinspection unchecked
                mMruList = new Gson().fromJson(mruString, ArrayList.class);

                // MRU lista mora imati maksimalnu velicinu
                if (mMruList != null) {
                    while (mMruList.size() > MAX_MRU_LIST) {
                        mMruList.remove(mMruList.size() - 1);
                    }
                }
            } catch (Exception ignore) {
            }
        }

        mDrawerCounter = preferences.getInt("drawercounter", MAX_DRAWER_OPEN);
    }

    private void selectItem(int position) {
        mDrawerList.setItemChecked(position, true);
        mDrawerLayout.closeDrawer(mDrawerList);

        try {
            Object selectedObject = mDrawerList.getItemAtPosition(position);
            if (null == selectedObject) {
                return;
            }
            String selectText = selectedObject.toString();

            // XXX: ugly as hell
            if (selectText.contains(MRU_DELIMITER)) {
                String[] selectParts = selectText.split(MRU_DELIMITER);
                if (selectParts.length == 2) {
                    autoCompleteTextViewNKOD1.setText(selectParts[0]);
                    autoCompleteTextViewNKDO1.setText(selectParts[1]);

                    showSchedule(R.id.leftDrawer1);
                }
            }
        } catch (ArrayIndexOutOfBoundsException ignore) {
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDrawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Crashlytics crashlytics = new Crashlytics.Builder().disabled(BuildConfig.DEBUG).build();
        Fabric.with(this, crashlytics, new Crashlytics());

        setContentView(R.layout.main);

        // inicijalizacija action bara
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setIcon(R.drawable.icon);
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
        }

        // inicijalizacija Google API klijenta
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

        // zahtjev za osvjezavanjem lokacije
        mLocationRequest = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(20 * 1000)        // 20 seconds
                .setFastestInterval(5 * 1000); // 5 seconds

        // polazni, odredisni i via kolodvor
        autoCompleteTextViewNKOD1 = (AutoCompleteTextView) findViewById(R.id.autoCompleteNKOD1);
        autoCompleteTextViewNKDO1 = (AutoCompleteTextView) findViewById(R.id.autoCompleteNKDO1);
        autoCompleteTextViewK1 = (AutoCompleteTextView) findViewById(R.id.autoCompleteK1);

        // ucitavanje prethodnih postavki iz preferenci
        readPrefs();

        // override za vrijeme polaska
        mTravelDay = Calendar.getInstance();
        editTextODH1 = (EditText) findViewById(R.id.editTextODH1);
        editTextODH1.setText(String.format(HR_LOCALE, DEFAULT_HR_DATE_FORMAT, mTravelDay,
                mTravelDay, mTravelDay, mTravelDay));
        editTextODH1.setOnClickListener(this);

        // inicijalizacija FAB
        View fab = findViewById(R.id.fab);
        assert fab != null;
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSchedule(R.id.fab);
            }
        });

        // inicijalizacija Navigation Drawera
        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawerLayout1);
        mDrawerList = (ListView) findViewById(R.id.leftDrawer1);
        mDrawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START);

        // dodavanje headera u iteme od Nav Drawera
        LayoutInflater tmpInflater = getLayoutInflater();
        ViewGroup mTop = (ViewGroup) tmpInflater.inflate(R.layout.drawer_header, mDrawerList, false);
        mDrawerList.addHeaderView(mTop, null, false);

        // popunjavanje Navigation Drawera
        if (mMruList != null) {
            mDrawerList.setAdapter(new ArrayAdapter<>(this, R.layout.drawer_list_item, mMruList));
        }

        // Navigation Drawer listeneri/akcije
        mDrawerList.setOnItemClickListener(new DrawerItemClickListener());
        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, R.string.drawer_open, R.string.drawer_close) {
            public void onDrawerClosed(View view) {
            }

            public void onDrawerOpened(View drawerView) {
            }
        };
        mDrawerLayout.setDrawerListener(mDrawerToggle);

        // maksimalan broj prikaza ladice
        if (mMruList != null && mDrawerCounter > 0) {
            mDrawerCounter--;
            mDrawerLayout.openDrawer(mDrawerList);

            if (mDrawerCounter == 0) {
                for (int i = 0; i < 2; i++) {
                    Toast.makeText(getApplicationContext(), R.string.drawer_bye,
                            Toast.LENGTH_LONG).show();
                }
            }
        }

        // progress dialog
        Thread thread = new Thread(this, TAG);
        thread.start();
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

        mGoogleApiClient.connect();

        // refresh drawera
        if (mMruList != null) {
            mDrawerList.setAdapter(null);
            mDrawerList.setAdapter(new ArrayAdapter<>(this, R.layout.drawer_list_item, mMruList));
        }
    }

    private String exportUpdateMru(String nkodText, String nkdoText) {
        List<String> newMruList = new ArrayList<>();

        // provjera jesu li kolodvori uopce ispravno uneseni
        if (nkodText != null && nkdoText != null && polazniList != null && dolazniList != null
                && polazniList.contains(nkodText) && dolazniList.contains(nkdoText)
                && (0 != nkodText.compareTo(nkdoText))) {
            // ako da, napravi separirani par
            String mruPair = nkodText + MRU_DELIMITER + nkdoText;
            newMruList.add(mruPair);

            // iteriraj nepraznu listu i izbaci zadnji, inace samo zamijeni null
            if (mMruList != null) {
                for (String elem : mMruList) {
                    if (newMruList.size() >= MAX_MRU_LIST) {
                        break;
                    }

                    if (!elem.equalsIgnoreCase(mruPair)) {
                        newMruList.add(elem);
                    }
                }
            }

            mMruList = newMruList;
        }

        return new Gson().toJson(mMruList);
    }

    private void writePrefs() {
        String nkodText = autoCompleteTextViewNKOD1.getText().toString()
                .trim();
        String nkdoText = autoCompleteTextViewNKDO1.getText().toString()
                .trim();
        String k1Text = autoCompleteTextViewK1.getText().toString().trim();

        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        String newMruList = exportUpdateMru(nkodText, nkdoText);

        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("autoCompleteNKOD1", nkodText);
        editor.putString("autoCompleteNKDO1", nkdoText);
        editor.putString("autoCompleteK1", k1Text);
        editor.putString("mruList", newMruList);
        editor.putInt("drawercounter", mDrawerCounter);

        editor.apply();
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

        writePrefs();
    }

    public void onBackPressed() {
        isBackPressed = true;
        super.onBackPressed();
    }

    public void onDestroy() {
        // XXX: potencijalni problem, s obzirom da se ne zove na kraju
        super.onDestroy();

        if (isFinishing() && !isBackPressed) {
            android.os.Process.killProcess(android.os.Process.myPid());
        } else {
            isBackPressed = false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_LOCATION) {
            if (grantResults.length == 1
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                //noinspection ResourceType
                mLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
                mLocationDenied = false;
            } else {
                mLocationDenied = true;
            }
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        Location location;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // trazit cemo dozvolu samo jednom
            if (!mLocationDenied) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_CODE_LOCATION);
            }
        } else {
            location = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);

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
        getMenuInflater().inflate(R.menu.menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    private void showSchedule(int itemid) {
        // provjeri jesu li kolovori inicijalizirani
        if (null == autoCompleteTextViewNKOD1 || null == autoCompleteTextViewNKDO1
                || null == polazniList || null == dolazniList)
            return;

        // provjeri polazni kolodvor
        if (!polazniList.contains(autoCompleteTextViewNKOD1.getText().toString())) {
            Toast.makeText(getApplicationContext(), R.string.neispravan_nkod,
                    Toast.LENGTH_LONG).show();
            return;
            // provjeri odredisni kolodvor
        } else if (!dolazniList.contains(autoCompleteTextViewNKDO1.getText()
                .toString())) {
            Toast.makeText(getApplicationContext(), R.string.neispravan_nkdo,
                    Toast.LENGTH_LONG).show();
            return;
        } else {
            // provjeri jesu li kolovori identicni
            if (0 == autoCompleteTextViewNKOD1.getText().toString()
                    .compareTo(autoCompleteTextViewNKDO1.getText().toString())) {
                Toast.makeText(getApplicationContext(), R.string.isti_kolodvori,
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        Bundle bundle = new Bundle();
        String hznetSchedUrl = getHznetSchedUrl(itemid);
        bundle.putString("hznetSchedUrl", hznetSchedUrl);
        bundle.putString("hznetSchedMd5Url", getHznetSchedMD5Url(hznetSchedUrl));
        Intent intent = new Intent(HZnet.this, HZnetWebParse.class);
        intent.putExtras(bundle);
        startActivityForResult(intent, 0);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        switch (item.getItemId()) {
            case R.id.prikaziD:
            case R.id.prikaziS:
            case R.id.prikaziA:
                showSchedule(item.getItemId());
                break;
            case R.id.odredi:
                stanicaNKOD1 = getStationFromLocation();
                if (null != stanicaNKOD1) {
                    @SuppressWarnings("unchecked")
                    FuzzyStringArrayAdapter<String> adapter = (FuzzyStringArrayAdapter<String>) autoCompleteTextViewNKOD1
                            .getAdapter();

                    //noinspection ConstantConditions
                    autoCompleteTextViewNKOD1.setAdapter(null);
                    autoCompleteTextViewNKOD1.setText(stanicaNKOD1);
                    autoCompleteTextViewNKOD1.setAdapter(adapter);

                    Toast.makeText(getApplicationContext(), R.string.odredjena_lokacija,
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getApplicationContext(), R.string.lokacija_greska,
                            Toast.LENGTH_SHORT).show();
                }
                break;
            case R.id.zamijeni:
                if (null != autoCompleteTextViewNKOD1
                        && null != autoCompleteTextViewNKDO1) {
                    @SuppressWarnings("unchecked")
                    FuzzyStringArrayAdapter<String> adapter = (FuzzyStringArrayAdapter<String>) autoCompleteTextViewNKOD1
                            .getAdapter();
                    String nkod1Text = autoCompleteTextViewNKOD1.getText().toString();

                    //noinspection ConstantConditions
                    autoCompleteTextViewNKOD1.setAdapter(null);
                    autoCompleteTextViewNKOD1.setText(autoCompleteTextViewNKDO1.getText()
                            .toString());
                    autoCompleteTextViewNKOD1.setAdapter(adapter);

                    //noinspection unchecked
                    adapter = (FuzzyStringArrayAdapter<String>) autoCompleteTextViewNKDO1
                            .getAdapter();
                    autoCompleteTextViewNKDO1.setAdapter(null);
                    autoCompleteTextViewNKDO1.setText(nkod1Text);
                    autoCompleteTextViewNKDO1.setAdapter(adapter);

                    Toast.makeText(getApplicationContext(), R.string.zamijenjeni_kolodvori,
                            Toast.LENGTH_SHORT).show();
                }
                break;
            case R.id.karta:
                // neki uredjaji (npr. Prestigio) nemaju Google Mapse
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    try {
                        Intent intentMapView = new Intent(HZnet.this, HZnetMapFragment.class);
                        startActivityForResult(intentMapView, 0);
                    } catch (NoClassDefFoundError e) {
                        Crashlytics.getInstance().core.logException(e);
                    }
                }
                break;
            case R.id.about:
                final SpannableString mContent = new SpannableString(getString(R.string.about));
                Linkify.addLinks(mContent, Linkify.EMAIL_ADDRESSES | Linkify.WEB_URLS
                        | Linkify.MAP_ADDRESSES);
                TextView aboutTextView = new TextView(HZnet.this);
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
                                HZnet.this.finish();
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
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.editTextODH1:
                DateTimeSlider.OnDateSetListener mDateSetListener = new DateSlider.OnDateSetListener() {
                    public void onDateSet(DateSlider view, Calendar selectedDate) {
                        HZnet.this.mTravelDay = selectedDate;
                        HZnet.this.editTextODH1.setText(String.format(DEFAULT_HR_DATE_FORMAT,
                                selectedDate, selectedDate, selectedDate, selectedDate));
                    }
                };
                DateTimeSlider mDateTimeSlider = new DateTimeSlider(this, mDateSetListener,
                        mTravelDay);
                mDateTimeSlider.mLocale = HR_LOCALE;
                mDateTimeSlider.show();
                break;
            default:
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (0 == requestCode) {
            if (RESULT_FIRST_USER == resultCode) {
                HZnet.this.finish();
            }
        } else if (CONNECTION_FAILURE_RESOLUTION_REQUEST == requestCode) {
            mResolvingError = false;
            if (resultCode == RESULT_OK) {
                if (!mGoogleApiClient.isConnecting() &&
                        !mGoogleApiClient.isConnected()) {
                    mGoogleApiClient.connect();
                }
            }
        }
    }

    static class HHandler extends Handler {
        private final WeakReference<HZnet> mTarget;

        public HHandler(HZnet target) {
            mTarget = new WeakReference<>(target);
        }

        @Override
        public void handleMessage(Message msg) {
            HZnet target = mTarget.get();
            if (null == target) {
                return;
            }

            // postavljanje listi kolodvora na spinnere
            if (null != target.polazniList && null != target.dolazniList && null != target.viaList) {
                // polazni kolodvor
                FuzzyStringArrayAdapter<String> adapterNKOD1 = new FuzzyStringArrayAdapter<>(
                        target, R.layout.list_item, target.polazniList);
                target.autoCompleteTextViewNKOD1.setAdapter(adapterNKOD1);

                // postavi polazni kolodvor iz autolokacije (ali samo ako vec
                // nema upisanog teksta)
                if (null != target.stanicaNKOD1) {
                    if (0 == target.autoCompleteTextViewNKOD1.getText().toString().trim().length()) {
                        target.autoCompleteTextViewNKOD1.setText(target.stanicaNKOD1);
                    }
                }

                // odredisni kolodvor
                FuzzyStringArrayAdapter<String> adapterNKDO1 = new FuzzyStringArrayAdapter<>(
                        target, R.layout.list_item, target.dolazniList);
                target.autoCompleteTextViewNKDO1.setAdapter(adapterNKDO1);

                // via kolodvor
                FuzzyStringArrayAdapter<String> adapterK1 = new FuzzyStringArrayAdapter<>(
                        target, R.layout.list_item, target.viaList);
                target.autoCompleteTextViewK1.setAdapter(adapterK1);

                // zatvori progress bar
                target.setSupportProgressBarIndeterminateVisibility(false);
            } else {
                target.setSupportProgressBarIndeterminateVisibility(false);
                target.showErrorAndExit(R.string.dohvat_greska);
            }
        }
    }

    private class DrawerItemClickListener implements ListView.OnItemClickListener {
        @Override
        public void onItemClick(AdapterView parent, View view, int position, long id) {
            selectItem(position);
        }
    }
}
