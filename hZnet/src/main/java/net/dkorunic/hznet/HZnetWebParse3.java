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

import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.text.SpannableString;
import android.text.util.Linkify;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

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

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class HZnetWebParse3 extends AppCompatActivity implements Runnable {
    private static final String TAG = HZnetWebParse3.class.getSimpleName();
    private static final String HZNET_CHARSET = "windows-1250"; //$NON-NLS-1$
    private static final int TEXTVIEW_MIN_HEIGHT = 50;
    private static final int MAGIC_MAX_RECURSE_LEVEL = 2;
    private static final Pattern BLACKLIST_PATTERN = Pattern
            .compile(
                    ".*(Plan putovanja|Kolodvor|Dolazak|Odlazak|Äekanje|VLAK|Kategorija i sastav|Simbol|ZnaÄenje|Legenda|Vagoni prvog razreda|Vagoni drugog razreda|MoguÄa rezervacija|Buffet-vagon|Prijevoz bicikala vlakom).*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); //$NON-NLS-1$
    private final HHandler handler = new HHandler(this);
    private TableLayout centralTable;
    private String hznetStationsUrl;
    private TagNode hznetTrainNodes;
    private int tableCounter;

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
                        HZnetWebParse3.this.finish();
                    }
                });
        AlertDialog dialogQuit = dialogErrorAndExit.create();
        try {
            dialogQuit.show();
        } catch (Exception e) {
            // catch FC in case that the app has already went away
        }
    }

    private void serializeTagNodes(TagNode tagNode, TableLayout oldTableLayout, TableRow oldTableRow) {
        @SuppressWarnings("unchecked")
        List tagChildren = tagNode.getAllChildren();
        TableLayout currentTableLayout = oldTableLayout;
        TableRow currentTableRow = oldTableRow;
        String tagNodeName = tagNode.getName();

        // otvoren tag
        if (0 == tagNodeName.compareToIgnoreCase("tbody")) {
            if (tableCounter == MAGIC_MAX_RECURSE_LEVEL) {
                View ruler = new View(HZnetWebParse3.this);
                ruler.setBackgroundColor(Color.GRAY);
                currentTableLayout.addView(ruler, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 2));
            }
        }

        for (Object child : tagChildren) {

            // imamo novi tag
            if (child instanceof TagNode) {
                TagNode tag = (TagNode) child;
                String tagName = tag.getName();

                // nova tablica
                if (0 == tagName.compareToIgnoreCase("tbody")) {
                    currentTableLayout = new TableLayout(HZnetWebParse3.this);
                    // nova tablica, ali trenutni redak nije prazan
                    if (null != oldTableRow) {
                        currentTableRow = null;

                        // postojeci redak u staru/trenutnu tablicu
                        oldTableRow.addView(currentTableLayout);
                    } else {
                        // direktno u staru/trenutnu tablicu
                        oldTableLayout.addView(currentTableLayout);
                    }
                    tableCounter++;
                } else if (0 == tagName.compareToIgnoreCase("tr")) {
                    currentTableRow = new TableRow(HZnetWebParse3.this);
                }

                // dublje u rekurziju
                serializeTagNodes((TagNode) child, currentTableLayout, currentTableRow);

                // imamo sadrzaj pojedinog taga
            } else if (child instanceof ContentNode) {
                String content = child.toString().replaceAll("\\s+", " ").trim();

                if (!Utils.isEmptyString(content)) {
                    // odmah trimamo nepotrebne znakove
                    content = content.replaceAll(" +", " ");

                    // poseban slucaj -- ne postoje podaci za taj setup
                    if (content.contains("Isprika")) {
                        if (null == currentTableRow) {
                            currentTableRow = new TableRow(HZnetWebParse3.this);
                        }

                        TextView tv = new TextView(HZnetWebParse3.this);
                        tv.setTextColor(Color.RED);
                        tv.setMinHeight(TEXTVIEW_MIN_HEIGHT);
                        tv.setPadding(8, 10, 10, 8);
                        tv.setText(content);
                        currentTableRow.addView(tv);
                        oldTableLayout.addView(currentTableRow);
                        return;
                    }

                    // blacklisted stringovi
                    Matcher blacklistMatcher = BLACKLIST_PATTERN.matcher(content);
                    if (blacklistMatcher.matches() || 0 == tagNodeName.compareToIgnoreCase("h3")) {
                        continue;
                    }

                    if (null != currentTableRow) {
                        // provjeri treba li stvarati novi red za situaciju
                        // kad je ispis datuma, relacije ili dodatnih podataka
                        if (content.contains("Datum") || content.contains("Relacija")
                                || 0 == tagNodeName.compareToIgnoreCase("p")) {
                            currentTableLayout.removeView(currentTableRow);
                            currentTableLayout.addView(currentTableRow, new ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                            currentTableRow = new TableRow(HZnetWebParse3.this);
                            // preskoci ispis okvirnog izracuna karte
                        } else if (content.contains("Okvirni")) {
                            continue;
                        }
                    } else {
                        // generiraj novi prazni red
                        currentTableRow = new TableRow(HZnetWebParse3.this);
                    }
                    TextView tv = new TextView(HZnetWebParse3.this);

                    // osnovni propertyji
                    tv.setTextColor(Color.WHITE);
                    tv.setMinHeight(TEXTVIEW_MIN_HEIGHT);
                    tv.setPadding(8, 10, 10, 8);

                    // detekcija rezervacije
                    if (content.contains("Obavezna rezervacija")) {
                        tv.setTextColor(Color.RED);
                    }

                    // finalizacija stupca
                    tv.setText(content);
                    currentTableRow.addView(tv);

                    currentTableLayout.removeView(currentTableRow);
                    currentTableLayout.addView(currentTableRow, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            }
        }

        // zatvoren tag i dodajemo novi ruler u postojecu
        // tablicu
        if (0 == tagNodeName.compareToIgnoreCase("tr") && tagNode.getAllChildren().size() > 0) {
            if (tableCounter == MAGIC_MAX_RECURSE_LEVEL) {
                View ruler = new View(HZnetWebParse3.this);
                ruler.setBackgroundColor(Color.GRAY);
                currentTableLayout.addView(ruler, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }
    }

    private TagNode loadNodesFromUrl(final String urlString) {
        TagNode node = null;
        InputStreamReader inputSR = null;

        try {
            Charset charset = Charset.forName(HZNET_CHARSET);

            URL url = new URL(urlString);
            OkUrlFactory okHttpUrlFactory = new OkUrlFactory(new OkHttpClient());
            HttpURLConnection conn = okHttpUrlFactory.open(url);
            inputSR = new InputStreamReader(conn.getInputStream(), charset);

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
            transformations.addTransformation(new TagTransformation("a"));
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

    public void run() {
        // ucitaj sadrzaj stranice, parsiraj i prikazi
        hznetTrainNodes = loadNodesFromUrl(hznetStationsUrl);
        handler.sendEmptyMessage(0);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.webparseview3);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setIcon(R.drawable.icon);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setSubtitle(R.string.prikaz_stanice);
        }

        // centralna tablica
        centralTable = (TableLayout) findViewById(R.id.tableLayoutLateSched);

        // dohvat argumenata iz glavnog activityja
        Bundle bundle = getIntent().getExtras();
        hznetStationsUrl = bundle.getString("hznetStationsUrl");
        if (null == hznetStationsUrl) {
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
                Intent homeIntent = new Intent(this, HZnetWebParse.class);
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(homeIntent);
                return true;
            case R.id.karta:
                // neki uredjaji (npr. Prestigio) nemaju Google Mapse
                try {
                    Intent intentMapView = new Intent(HZnetWebParse3.this, HZnetMapFragment.class);
                    startActivityForResult(intentMapView, 0);
                } catch (NoClassDefFoundError e) {
                    Crashlytics.getInstance().core.logException(e);
                }
                break;
            case R.id.about:
                final SpannableString mContent = new SpannableString(getString(R.string.about));
                Linkify.addLinks(mContent, Linkify.EMAIL_ADDRESSES | Linkify.WEB_URLS
                        | Linkify.MAP_ADDRESSES);
                TextView aboutTextView = new TextView(HZnetWebParse3.this);
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
                                HZnetWebParse3.this.finish();
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (0 == requestCode) {
            if (RESULT_FIRST_USER == resultCode) {
                if (null == getParent()) {
                    setResult(RESULT_FIRST_USER);
                } else {
                    getParent().setResult(RESULT_FIRST_USER);
                }
                HZnetWebParse3.this.finish();
            }
        }
    }

    static class HHandler extends Handler {
        private final WeakReference<HZnetWebParse3> mTarget;

        public HHandler(HZnetWebParse3 target) {
            mTarget = new WeakReference<>(target);
        }

        @Override
        public void handleMessage(Message msg) {
            HZnetWebParse3 target = mTarget.get();
            if (null == target) {
                return;
            }

            if (null != target.hznetTrainNodes) {
                target.serializeTagNodes(target.hznetTrainNodes, target.centralTable, null);
                target.setSupportProgressBarIndeterminateVisibility(false);
            } else {
                target.setSupportProgressBarIndeterminateVisibility(false);
                target.showErrorAndExit(R.string.dohvat_greska);
            }
        }
    }
}
