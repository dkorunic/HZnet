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

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AlertDialog.Builder;
import android.support.v7.app.AppCompatActivity;
import android.text.SpannableString;
import android.text.util.Linkify;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;

import org.htmlcleaner.CleanerProperties;
import org.htmlcleaner.CleanerTransformations;
import org.htmlcleaner.ContentNode;
import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.TagNode;
import org.htmlcleaner.TagTransformation;
import org.htmlcleaner.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class HZnetWebParse extends AppCompatActivity implements Runnable {
    private static final String TAG = HZnetWebParse.class.getSimpleName();
    private static final String HZNET_CHARSET = "windows-1250"; //$NON-NLS-1$
    private static final String HZNET_TPVL_START_URL = "http://vred.hzinfra.hr/hzinfo/Default.asp?"; //$NON-NLS-1$
    private static final String HZNET_TPVL_END_URL = "Category=hzinfo&Service=tpvl&SCREEN=2"; //$NON-NLS-1$
    private static final String HZNET_DELIMITER = "&"; //$NON-NLS-1$
    private static final String MAGIC_STRING = "*"; //$NON-NLS-1$
    private static final int MAGIC_MAX_RECURSE_LEVEL = 6;
    private static final int MAGIC_MAX_RECURSE_LEVEL_PLUS = 9;
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{2}:\\d{2}$"); //$NON-NLS-1$
    private static final Pattern STATION_URL_PATTERN = Pattern.compile(
            ".*VL=\\.*([^&]+)&.*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); //$NON-NLS-1$
    private static final int MINUTES_IN_HOUR = 60;
    private static final int MINUTES_FOR_RED = 15;
    private static final int MINUTES_FOR_YELLOW = 30;
    private static final int MINUTES_FOR_GREEN = 60;
    private static final int READ_BUFFER_SIZE = 8192;
    private static final int TEXTVIEW_MIN_HEIGHT = 50;
    private static final int INITIAL_SB_CAPACITY = 10;
    private final HHandler handler = new HHandler(this);
    private TableLayout centralTable;
    private String hznetSchedUrl;
    private String hznetSchedMd5Url;
    private TagNode hznetSchedNodes;
    private Calendar calendarToday;
    private int tekuceMinute;
    private String selectedTrainId;
    private TableRow selectedRow = null;
    private Map<String, String> trainstationsHashMap;

    private void showErrorAndExit(final int resId) {
        Builder dialogErrorAndExit = new AlertDialog.Builder(this);
        dialogErrorAndExit.setMessage(getString(resId)).setCancelable(false)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (null == getParent()) {
                            setResult(RESULT_FIRST_USER);
                        } else {
                            getParent().setResult(RESULT_FIRST_USER);
                        }
                        HZnetWebParse.this.finish();
                    }
                });
        AlertDialog dialogQuit = dialogErrorAndExit.create();
        try {
            dialogQuit.show();
        } catch (Exception e) {
            // catch FC in case that the app has already went away
        }
    }

    private void serializeTagNodes(TagNode tagNode, TableLayout oldTableLayout,
                                   TableRow oldTableRow, int recursionLevel) {
        @SuppressWarnings("unchecked")
        List tagChildren = tagNode.getAllChildren();
        boolean newRow = false;
        boolean newTable = false;
        TableLayout currentTableLayout = oldTableLayout;
        TableRow currentTableRow = oldTableRow;

        // otvoren tag
        for (Object child : tagChildren) {

            // imamo novi tag
            if (child instanceof TagNode) {
                TagNode tag = (TagNode) child;
                String tagName = tag.getName();

                // nova tablica
                if (0 == tagName.compareToIgnoreCase("tbody")) {
                    currentTableLayout = new TableLayout(HZnetWebParse.this);
                    // nova tablica, ali trenutni redak nije prazan
                    if (null != oldTableRow) {
                        currentTableRow = null;
                    }
                    newTable = true;
                } else if (0 == tagName.compareToIgnoreCase("tr")) {
                    currentTableRow = new TableRow(HZnetWebParse.this);
                }

                // dublje u rekurziju
                serializeTagNodes((TagNode) child, currentTableLayout, currentTableRow,
                        recursionLevel + 1);

                // imamo sadrzaj pojedinog taga
            } else if (child instanceof ContentNode) {
                String content = child.toString().replaceAll("\\s+", " ").trim();

                if (!Utils.isEmptyString(content)) {
                    // odmah trimamo nepotrebne znakove
                    content = content.replaceAll(" +", " ");

                    // poseban slucaj -- ne postoje podaci za taj setup
                    if (content.contains("ne postoje podaci")) {
                        if (null == currentTableRow) {
                            currentTableRow = new TableRow(HZnetWebParse.this);
                        }

                        TextView tv = new TextView(HZnetWebParse.this);
                        tv.setTextColor(Color.RED);
                        tv.setMinHeight(TEXTVIEW_MIN_HEIGHT);
                        tv.setPadding(8, 10, 10, 8);
                        tv.setText(content);
                        currentTableRow.addView(tv);
                        oldTableLayout.addView(currentTableRow);
                        return;
                    }

                    if (0 == tagNode.getName().compareToIgnoreCase("h3")) {
                        continue;
                    }

                    // parsiramo i obradjujemo samo podatke za koje znamo da su
                    // dovoljno duboko (sto je potencijalna greska i ne previse
                    // fleksibilno, znam)
                    if (null != currentTableRow) {
                        // provjeri treba li stvarati novi red za situaciju
                        // kad je ispis datuma i relacije ili dodatnih podataka
                        if (content.contains("Datum") || content.contains("Relacija")
                                || 0 == tagNode.getName().compareToIgnoreCase("p")) {
                            currentTableLayout.removeView(currentTableRow);
                            currentTableLayout.addView(currentTableRow, new ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                            currentTableRow = new TableRow(HZnetWebParse.this);
                            // preskoci ispis okvirnog izracuna karte
                        } else if (content.contains("Okvirni")) {
                            continue;
                        }
                    } else {
                        // generiraj novi prazni red
                        currentTableRow = new TableRow(HZnetWebParse.this);
                    }
                    TextView tv = new TextView(HZnetWebParse.this);

                    // osnovni propertyji
                    tv.setTextColor(Color.WHITE);
                    tv.setMinHeight(TEXTVIEW_MIN_HEIGHT);
                    tv.setPadding(8, 10, 10, 8);

                    // centralni dio za odredjivanje boje s obzirom na
                    // vremenski offset
                    if (recursionLevel < MAGIC_MAX_RECURSE_LEVEL) {
                        Matcher dateMatcher = DATE_PATTERN.matcher(content);

                        // provjeri vrijeme i podesi boju
                        // provjeri ima li smisla postavljati boju
                        if (dateMatcher.matches()) {
                            try {
                                // parsiranje vremena i izracun u minutama
                                Calendar calendarParsed = (Calendar) calendarToday.clone();
                                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);
                                Date dateParsed = sdf.parse(content);
                                calendarParsed.setTime(dateParsed);
                                int polazneMinute = calendarParsed.get(Calendar.HOUR_OF_DAY)
                                        * MINUTES_IN_HOUR + calendarParsed.get(Calendar.MINUTE);

                                // da li je polazno vrijeme unutar tekuceg
                                // sata
                                if (polazneMinute > tekuceMinute
                                        && polazneMinute - tekuceMinute < MINUTES_FOR_GREEN) {
                                    // crveno: <15 minuta za polazak
                                    if (polazneMinute - tekuceMinute < MINUTES_FOR_RED) {
                                        tv.setTextColor(Color.RED);
                                        // zuto: >15 i <30 minuta
                                    } else if (polazneMinute - tekuceMinute < MINUTES_FOR_YELLOW) {
                                        tv.setTextColor(Color.YELLOW);
                                    } else {
                                        // zeleno: >30 i <60 minuta
                                        tv.setTextColor(Color.GREEN);
                                    }
                                    // oznaci sa zvjezdicom
                                    content += MAGIC_STRING;
                                }
                            } catch (java.text.ParseException ignored) {
                            }
                        }
                    }

                    // smanji intenzitet manje vaznih podataka
                    if (recursionLevel > MAGIC_MAX_RECURSE_LEVEL_PLUS) {
                        tv.setTextColor(Color.GRAY);
                    }

                    // finalizacija stupca
                    tv.setText(content);
                    currentTableRow.addView(tv);
                    newRow = true;
                }
            }
        }

        // zatvoren tag i dodajemo novi redak u postojecu tablicu
        if (newRow && currentTableRow != null) {
            currentTableLayout.removeView(currentTableRow);
            currentTableLayout.addView(currentTableRow, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // horizontalni razdjelnik pojedinog retka
            if (recursionLevel < MAGIC_MAX_RECURSE_LEVEL
                    && 0 == tagNode.getName().compareToIgnoreCase("td")) {
                View ruler = new View(HZnetWebParse.this);
                ruler.setBackgroundColor(Color.GRAY);
                currentTableLayout.addView(ruler, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));

                registerForContextMenu(currentTableRow);
                currentTableRow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openContextMenu(v);
                    }
                });
            }
        }

        // zatvoren tag i dodajemo novu tablicu u...
        if (newTable) {
            if (null != oldTableRow) {
                // postojeci redak u staru/trenutnu tablicu
                oldTableRow.addView(currentTableLayout);
            } else {
                // direktno u staru/trenutnu tablicu
                oldTableLayout.addView(currentTableLayout);
            }
        }
    }

    private void getTrainStations(TagNode tagNode) {
        TagNode[] links = (tagNode).getElementsHavingAttribute("href", true);
        trainstationsHashMap = new HashMap<>(32, 0.75f);

        for (TagNode t : links) {
            String link = t.getAttributeByName("href");
            // popravljanje HZ neispravnog linka (DOH i ODH sadrze smece)
            link = link.replaceAll("&DOH=[^&]*", "&DOH=").replaceAll("&ODH=[^&]*", "&ODH=");

            // provjera da li linkovi odgovaraju standardnom URL-u koji sadrzi
            // VL= ID vlaka i OKL/DOKL nizove
            Matcher uriMatcher = STATION_URL_PATTERN.matcher(link);
            if (uriMatcher.matches() && uriMatcher.groupCount() > 0) {
                trainstationsHashMap.put(uriMatcher.group(1), link);
            }
        }
    }

    private TagNode loadNodesFromUrl(final String urlString) {
        TagNode node = null;
        InputStreamReader inputSR = null;
        OutputStreamWriter outputSR = null;

        try {
            Charset charset = Charset.forName(HZNET_CHARSET);

            // dohvati iz cachea ako postoji datoteka
            File file = new File(getCacheDir(), hznetSchedMd5Url);
            if (file.exists() && file.isFile()) {
                inputSR = new InputStreamReader(new FileInputStream(file), charset);
                Crashlytics.getInstance().core.log(Log.DEBUG, TAG, "Schedule fetched from local cache ID "
                        + hznetSchedMd5Url);
            } else {
                // ne postoji datoteka, pa nam slijedi URL fetch
                URL url = new URL(urlString);
                OkUrlFactory okHttpUrlFactory = new OkUrlFactory(new OkHttpClient());
                HttpURLConnection conn = okHttpUrlFactory.open(url);
                inputSR = new InputStreamReader(conn.getInputStream(), charset);

                // dohvat sadrzaja i spremanje u cache datoteku
                try {
                    //noinspection ResultOfMethodCallIgnored
                    file.createNewFile();
                    outputSR = new OutputStreamWriter(new FileOutputStream(file), charset);
                    char[] buf = new char[READ_BUFFER_SIZE];
                    int len;

                    while ((len = inputSR.read(buf)) > 0) {
                        outputSR.write(buf, 0, len);
                    }
                    Crashlytics.getInstance().core.log(Log.DEBUG, TAG,
                            "Schedule downloaded from network and saved to cache ID "
                                    + hznetSchedMd5Url);
                } catch (Exception e) {
                    // Crashlytics.getInstance().core.logException(e);
                } finally {
                    //noinspection ConstantConditions
                    if (null != inputSR) {
                        try {
                            inputSR.close();
                        } catch (IOException ignored) {
                        }
                    }
                    if (null != outputSR) {
                        try {
                            outputSR.close();
                        } catch (IOException ignored) {
                        }
                    }
                }

                // ako je uspjelo snimanje cachea, mozemo zamijeniti ulazni
                // stream
                if (file.exists() && file.isFile()) {
                    inputSR = new InputStreamReader(new FileInputStream(file), charset);
                    Crashlytics.getInstance().core.log(Log.DEBUG, TAG, "Schedule fetched from local cache ID "
                            + hznetSchedMd5Url);
                }
            }

            // priprema htmlcleanera
            HtmlCleaner parser = new HtmlCleaner();
            CleanerProperties props = parser.getProperties();
            props.setAllowHtmlInsideAttributes(true);
            props.setAllowMultiWordAttributes(true);
            props.setRecognizeUnicodeChars(true);
            props.setOmitComments(true);

            props.setOmitXmlDeclaration(true);
            props.setIgnoreQuestAndExclam(true);
            props.setPruneTags("head,script,style,image,img,hr,br,th,input");

            CleanerTransformations transformations = new CleanerTransformations();
            transformations.addTransformation(new TagTransformation("form"));
            transformations.addTransformation(new TagTransformation("font"));
            transformations.addTransformation(new TagTransformation("b"));
            transformations.addTransformation(new TagTransformation("strong"));
            transformations.addTransformation(new TagTransformation("i"));
            props.setCleanerTransformations(transformations);

            // html cleaning
            node = parser.clean(inputSR);
        } catch (Exception e) {
            // Crashlytics.getInstance().core.logException(e);
        } finally {
            if (null != inputSR) {
                try {
                    inputSR.close();
                } catch (IOException ignored) {
                }
            }
        }
        return node;
    }

    private String getHznetTrainUrl() {
        // pozvana je provjera bez selektiranog vlaka
        if (null == selectedTrainId) {
            return null;
        }

        StringBuilder sb = new StringBuilder(INITIAL_SB_CAPACITY);
        sb.append(HZNET_TPVL_START_URL);

        // oznaka vlaka (VL)
        sb.append("VL=");
        try {
            sb.append(URLEncoder.encode(selectedTrainId, HZNET_CHARSET));
        } catch (UnsupportedEncodingException e) {
            sb.append(selectedTrainId);
        }
        sb.append(HZNET_DELIMITER);

        // kraj
        sb.append(HZNET_TPVL_END_URL);

        Crashlytics.getInstance().core.log(Log.DEBUG, TAG, "Will fetch train late schedule for train "
                + selectedTrainId + " from <URL:" + sb.toString() + ">");
        return sb.toString();
    }

    private String getHznetStationsUrl() {
        // pozvana je provjera bez selektiranog vlaka
        if (null == selectedTrainId) {
            return null;
        }

        String encodedStationsUrl = null;

        if (null != trainstationsHashMap && trainstationsHashMap.containsKey(selectedTrainId)) {
            // znamo cijeli URL za pojedinu oznaku vlaka
            encodedStationsUrl = trainstationsHashMap.get(selectedTrainId).trim()
                    .replaceAll(" ", "");
            Crashlytics.getInstance().core.log(Log.DEBUG, TAG, "Will fetch train stations list for train "
                    + selectedTrainId + " from <URL:" + encodedStationsUrl + ">");
        } else {
            Crashlytics.getInstance().core.log(Log.ERROR, TAG,
                    "Could not find requested train in stations map, train ID "
                            + selectedTrainId);
        }
        return encodedStationsUrl;
    }

    public void run() {
        // osvjezi vrijeme i izracun tekucih minuta
        calendarToday = new GregorianCalendar();
        tekuceMinute = calendarToday.get(Calendar.HOUR_OF_DAY) * MINUTES_IN_HOUR
                + calendarToday.get(Calendar.MINUTE);

        // ucitaj sadrzaj stranice, parsiraj i prikazi
        hznetSchedNodes = loadNodesFromUrl(hznetSchedUrl);
        handler.sendEmptyMessage(0);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.webparseview);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setIcon(R.drawable.icon);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setSubtitle(R.string.prikaz_raspored);
        }

        // centralna tablica
        centralTable = (TableLayout) findViewById(R.id.tableLayoutSched);

        // dohvat argumenata iz glavnog activityja
        Bundle bundle = getIntent().getExtras();
        hznetSchedUrl = bundle.getString("hznetSchedUrl");
        hznetSchedMd5Url = bundle.getString("hznetSchedMd5Url");
        if (null == hznetSchedUrl || null == hznetSchedMd5Url) {
            Crashlytics.getInstance().core.log(Log.ERROR, TAG, "Did not get Bundle from main Activity");
            showErrorAndExit(R.string.dohvat_greska);
        }

        // dohvati...
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
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.menu_web_parse, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                Intent homeIntent = new Intent(this, HZnet.class);
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(homeIntent);
                return true;
            case R.id.about:
                final SpannableString mContent = new SpannableString(getString(R.string.about));
                Linkify.addLinks(mContent, Linkify.EMAIL_ADDRESSES | Linkify.WEB_URLS
                        | Linkify.MAP_ADDRESSES);
                TextView aboutTextView = new TextView(HZnetWebParse.this);
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
            case R.id.karta:
                // neki uredjaji (npr. Prestigio) nemaju Google Mapse
                try {
                    Intent intentMapView = new Intent(HZnetWebParse.this, HZnetMapFragment.class);
                    startActivityForResult(intentMapView, 0);
                } catch (NoClassDefFoundError e) {
                    Crashlytics.getInstance().core.logException(e);
                }
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
                                HZnetWebParse.this.finish();
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

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        if (v instanceof TableRow) {
            TableRow row = (TableRow) v;

            if (null != selectedRow) {
                selectedRow.setBackgroundColor(Color.TRANSPARENT);
            }

            TextView tv = ((TextView) row.getChildAt(1));
            if (null != tv) {
                row.setBackgroundColor(Color.DKGRAY);
                getMenuInflater().inflate(R.menu.menu_web_parse_context, menu);
                selectedTrainId = tv.getText().toString().trim().replaceAll(" ", ".");
                selectedRow = row;
            }
        }
    }

    @Override
    public boolean onContextItemSelected(android.view.MenuItem item) {
        switch (item.getItemId()) {
            case R.id.provjeriKasnjenje:
                String tmpHznetTrainUrl = getHznetTrainUrl();
                if (null == selectedTrainId || null == tmpHznetTrainUrl) {
                    Toast.makeText(getApplicationContext(), R.string.neispravan_vlak,
                            Toast.LENGTH_SHORT).show();
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putString("hznetTrainUrl", tmpHznetTrainUrl);
                    Intent intent = new Intent(HZnetWebParse.this, HZnetWebParse2.class);
                    intent.putExtras(bundle);
                    startActivityForResult(intent, 0);
                }
                break;
            case R.id.listaStanica:
                String tmpHznetStationsUrl = getHznetStationsUrl();
                if (null == selectedTrainId || null == tmpHznetStationsUrl) {
                    Toast.makeText(getApplicationContext(), R.string.neispravan_vlak,
                            Toast.LENGTH_SHORT).show();
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putString("hznetStationsUrl", tmpHznetStationsUrl);
                    Intent intent = new Intent(HZnetWebParse.this, HZnetWebParse3.class);
                    intent.putExtras(bundle);
                    startActivityForResult(intent, 0);
                }
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (0 == requestCode) {
            if (RESULT_FIRST_USER == resultCode) {
                if (null == getParent()) {
                    setResult(RESULT_FIRST_USER);
                } else {
                    getParent().setResult(RESULT_FIRST_USER);
                }
                HZnetWebParse.this.finish();
            }
        }
    }

    static class HHandler extends Handler {
        private final WeakReference<HZnetWebParse> mTarget;

        public HHandler(HZnetWebParse target) {
            mTarget = new WeakReference<>(target);
        }

        @Override
        public void handleMessage(Message msg) {
            HZnetWebParse target = mTarget.get();
            if (null == target) {
                return;
            }

            if (null != target.hznetSchedNodes) {
                // odlazni i dolazni kolodvori za pojedini vlak
                target.getTrainStations(target.hznetSchedNodes);

                // TODO: dodati header ispis
                target.serializeTagNodes(target.hznetSchedNodes, target.centralTable, null, 0);
                target.setSupportProgressBarIndeterminateVisibility(false);
            } else {
                target.setSupportProgressBarIndeterminateVisibility(false);
                target.showErrorAndExit(R.string.dohvat_greska);
            }
        }
    }
}
