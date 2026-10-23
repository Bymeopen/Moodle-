package id.pribadi.minilms;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Pembungkus WebView untuk server Mini LMS. Aplikasi ini hanya klien: server (npm start) harus berjalan
 * dan bisa dijangkau dari ponsel. Alamat server disimpan di SharedPreferences.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "minilms";
    private static final String KEY_URL = "server_url";

    private SharedPreferences prefs;
    private WebView web;
    private LinearLayout setup;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        web = buildWebView();
        setup = buildSetupView();
        root.addView(setup, new LinearLayout.LayoutParams(-1, -1));
        root.addView(web, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);

        String url = prefs.getString(KEY_URL, null);
        if (url == null) showSetup(null); else openServer(url);
    }

    // ---------- layar pengaturan server ----------
    private LinearLayout buildSetupView() {
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Mini LMS");
        title.setTextSize(28);
        TextView hint = new TextView(this);
        hint.setText("Masukkan alamat server Mini LMS Anda, mis. http://192.168.1.10:3000 (PC yang menjalankan npm start) atau https://lms.domain-anda.id");
        hint.setPadding(0, pad / 2, 0, pad / 2);

        EditText input = new EditText(this);
        input.setHint("http://192.168.1.10:3000");
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setText(prefs.getString(KEY_URL, ""));
        input.setId(View.generateViewId());

        status = new TextView(this);
        status.setTextColor(0xFFC0392B);

        Button go = new Button(this);
        go.setText("Sambungkan");
        View.OnClickListener connect = v -> {
            String url = normalize(input.getText().toString());
            if (url == null) { status.setText("Alamat tidak valid. Awali dengan http:// atau https://"); return; }
            prefs.edit().putString(KEY_URL, url).apply();
            openServer(url);
        };
        go.setOnClickListener(connect);
        input.setOnEditorActionListener((v, action, e) -> { connect.onClick(v); return true; });

        box.addView(title); box.addView(hint); box.addView(input); box.addView(status); box.addView(go);
        return box;
    }

    /** Terima hanya http(s) dan buang slash akhir; tolak skema lain (javascript:, file:, dll). */
    static String normalize(String raw) {
        String s = raw.trim();
        if (!s.matches("(?i)^https?://[^\\s/]+(/\\S*)?$")) return null;
        return s.replaceAll("/+$", "");
    }

    private void showSetup(String error) {
        status.setText(error == null ? "" : error);
        web.setVisibility(View.GONE);
        setup.setVisibility(View.VISIBLE);
    }

    private void openServer(String url) {
        setup.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(url);
    }

    // ---------- WebView ----------
    private WebView buildWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // token login disimpan di localStorage
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(false);

        w.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showSetup("Tidak bisa terhubung ke server (" + e.getDescription() + "). Cek alamat dan pastikan server menyala.");
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // tetap di server sendiri; tautan luar (mis. Moodle kampus) dibuka di browser
                Uri u = r.getUrl(), base = Uri.parse(prefs.getString(KEY_URL, ""));
                if (u.getHost() != null && u.getHost().equals(base.getHost()) && u.getPort() == base.getPort()) return false;
                try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap f) { }
        });

        // WebView bawaan menelan alert()/confirm(); aplikasi web memakainya untuk konfirmasi hapus dan pesan galat
        w.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setPositiveButton("OK", (d, i) -> r.confirm()).setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                    .setPositiveButton("OK", (d, i) -> r.confirm()).setNegativeButton("Batal", (d, i) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
        });

        // unduhan file (URL bertiket dari server) -> DownloadManager, tersimpan di folder Unduhan
        w.setDownloadListener((url, ua, disposition, mime, len) -> {
            String name = URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            Toast.makeText(this, "Mengunduh " + name, Toast.LENGTH_SHORT).show();
        });
        return w;
    }

    // ---------- navigasi ----------
    @Override public void onBackPressed() {
        if (setup.getVisibility() == View.VISIBLE && prefs.getString(KEY_URL, null) != null) { openServer(prefs.getString(KEY_URL, "")); return; }
        if (web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    // menu titik tiga di action bar: ganti server
    @Override public boolean onCreateOptionsMenu(Menu menu) { menu.add(0, 1, 0, "Ganti server"); return true; }
    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 1) { showSetup(null); return true; }
        return super.onOptionsItemSelected(item);
    }
}
