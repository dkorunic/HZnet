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


public class HZnetWebParse2 extends AppCompatActivity implements Runnable {
    private static final String TAG = HZnetWebParse2.class.getSimpleName();
    private static final String HZNET_CHARSET = "windows-1250"; //$NON-NLS-1$
    private static final int TEXTVIEW_MIN_HEIGHT = 50;
    private static final int MAGIC_MAX_RECURSE_LEVEL = 8;
    private final HHandler handler = new HHandler(this);
    private TableLayout centralTable;
    private String hznetTrainUrl;
    private TagNode hznetTrainNodes;

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
                        HZnetWebParse2.this.finish();
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
                    currentTableLayout = new TableLayout(HZnetWebParse2.this);
                    // nova tablica, ali trenutni redak nije prazan
                    if (null != oldTableRow) {
                        currentTableRow = null;
                    }
                    newTable = true;
                } else if (0 == tagName.compareToIgnoreCase("td")) {
                    currentTableRow = new TableRow(HZnetWebParse2.this);
                }

                // dublje u rekurziju
                serializeTagNodes((TagNode) child, currentTableLayout, currentTableRow,
                        recursionLevel + 1);

                // imamo sadrzaj pojedinog taga
            } else if (child instanceof ContentNode) {
                String content = child.toString().replaceAll("\\s+", " ").trim();

                if (!Utils.isEmptyString(content)) {
                    // poseban slucaj -- ne postoje podaci za taj setup
                    if (content.contains("nije u evidenciji")) {
                        if (null == currentTableRow) {
                            currentTableRow = new TableRow(HZnetWebParse2.this);
                        }

                        TextView tv = new TextView(HZnetWebParse2.this);
                        tv.setTextColor(Color.RED);
                        tv.setMinHeight(TEXTVIEW_MIN_HEIGHT);
                        tv.setPadding(8, 10, 10, 8);
                        tv.setText(content);
                        currentTableRow.addView(tv);
                        oldTableLayout.addView(currentTableRow);
                        return;
                    }

                    // blacklisted stringovi
                    if (content.contains("Vozni red vlaka")
                            || content.contains("Pregled kretanja vlaka")
                            || content.contains("Pregled sastava vlaka")
                            || content.contains("Broj vlaka")
                            || content.contains("Nazad")
                            || 0 == tagNode.getName().compareToIgnoreCase("h3")) {
                        continue;
                    }

                    // odmah trimamo nepotrebne znakove
                    content = content.replaceAll(" +", " ").replaceAll(":$", "");

                    // generiraj novi prazni red
                    currentTableRow = new TableRow(HZnetWebParse2.this);

                    TextView tv = new TextView(HZnetWebParse2.this);
                    tv.setLayoutParams(new TableRow.LayoutParams(
                            TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT,
                            1f));

                    // osnovni propertyji
                    tv.setMinHeight(TEXTVIEW_MIN_HEIGHT);
                    tv.setPadding(8, 10, 10, 8);

                    if (0 == tagNode.getName().compareToIgnoreCase("i")) {
                        tv.setMinHeight(0);
                        tv.setPadding(8, 10, 8, 0);
                        tv.setTextColor(Color.LTGRAY);
                    } else {
                        // detekcija oznake kasnjenja
                        if (content.contains("Vlak je redovit")) {
                            tv.setTextColor(Color.GREEN);
                        } else if (content.contains("Kasni")) {
                            tv.setTextColor(Color.RED);
                        } else {
                            tv.setTextColor(Color.WHITE);
                        }
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
                    && 0 == tagNode.getName().compareToIgnoreCase("i")
                    && 0 == tagNode.getParent().getName().compareToIgnoreCase("td")) {
                View ruler = new View(HZnetWebParse2.this);
                ruler.setBackgroundColor(Color.GRAY);
                currentTableLayout.addView(ruler, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 2));
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
        hznetTrainNodes = loadNodesFromUrl(hznetTrainUrl);
        handler.sendEmptyMessage(0);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.webparseview2);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setIcon(R.drawable.icon);
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setSubtitle(R.string.prikaz_kasnjenje);
        }

        // centralna tablica
        centralTable = (TableLayout) findViewById(R.id.tableLayoutLateSched);

        // dohvat argumenata iz glavnog activityja
        Bundle bundle = getIntent().getExtras();
        hznetTrainUrl = bundle.getString("hznetTrainUrl");
        if (null == hznetTrainUrl) {
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
                    Intent intentMapView = new Intent(HZnetWebParse2.this, HZnetMapFragment.class);
                    startActivityForResult(intentMapView, 0);
                } catch (NoClassDefFoundError e) {
                    Crashlytics.getInstance().core.logException(e);
                }
                break;
            case R.id.about:
                final SpannableString mContent = new SpannableString(getString(R.string.about));
                Linkify.addLinks(mContent, Linkify.EMAIL_ADDRESSES | Linkify.WEB_URLS
                        | Linkify.MAP_ADDRESSES);
                TextView aboutTextView = new TextView(HZnetWebParse2.this);
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
                                HZnetWebParse2.this.finish();
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
                HZnetWebParse2.this.finish();
            }
        }
    }

    static class HHandler extends Handler {
        private final WeakReference<HZnetWebParse2> mTarget;

        public HHandler(HZnetWebParse2 target) {
            mTarget = new WeakReference<>(target);
        }

        @Override
        public void handleMessage(Message msg) {
            HZnetWebParse2 target = mTarget.get();
            if (null == target) {
                return;
            }

            if (null != target.hznetTrainNodes) {
                target.serializeTagNodes(target.hznetTrainNodes, target.centralTable, null, 0);
                target.setSupportProgressBarIndeterminateVisibility(false);
            } else {
                target.setSupportProgressBarIndeterminateVisibility(false);
                target.showErrorAndExit(R.string.dohvat_greska);
            }
        }
    }
}
