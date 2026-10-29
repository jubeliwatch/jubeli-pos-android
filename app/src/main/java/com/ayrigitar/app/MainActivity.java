package com.ayrigitar.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.SharedPreferences;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/* Satu WebView yang membuka situs flavor terpilih (Jubeli POS).
   Tambahan dari sisi aplikasi: izin mikrofon/kamera, pilih/ambil foto, simpan unduhan, cetak, tautan luar, dan berbagi file. */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 1, REQ_IZIN = 2, REQ_KAMERA = 3, REQ_BT = 4;
    private static final String IZIN_BT = "android.permission.BLUETOOTH_CONNECT";
    /* skema yang boleh dibuka di luar WebView; selain ini (file:, content:, javascript:, dll.) ditolak */
    private static final Set<String> SKEMA_LUAR = new HashSet<>(Arrays.asList("http", "https", "mailto", "tel", "sms", "geo", "whatsapp", "intent"));
    private static final long UMUR_CACHE_MS = 24L * 60 * 60 * 1000;
    private static final int MAKS_PESAN = 24 * 1024 * 1024;   /* batas ukuran pesan dari halaman (foto/cadangan sebagai base64) */
    /* dijalankan setiap halaman selesai dimuat: print() dan unduhan blob diteruskan ke aplikasi lewat AyriApp.postMessage */
    private static final String SKRIP = "(function(){if(window.__ayriApp||!window.AyriApp)return;window.__ayriApp=1;"
            + "window.print=function(){AyriApp.postMessage(JSON.stringify({op:'cetak',judul:document.title||'Jubeliwatch'}));};"
            + "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;"
            + "if(!a||!/^(blob|data):/.test(a.href))return;e.preventDefault();"
            + "fetch(a.href).then(function(r){return r.blob();}).then(function(b){var f=new FileReader();"
            + "f.onload=function(){AyriApp.postMessage(JSON.stringify({op:'simpanFile',nama:a.download||'unduhan',mime:b.type||'application/octet-stream',b64:String(f.result).split(',')[1]||''}));};f.readAsDataURL(b);});"
            + "},true);})();";

    private WebView web;
    private String host, awal;
    private ValueCallback<Uri[]> fileCallback;
    private WebChromeClient.FileChooserParams fileParams;
    private Uri fotoKamera;
    private PermissionRequest izinTertunda;
    private Runnable tugasBt;
    /* satu cetakan pada satu waktu, di luar thread tampilan */
    private final ExecutorService antrianCetak = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle simpanan) {
        super.onCreate(simpanan);
        awal = getString(R.string.alamat_awal);
        host = Uri.parse(awal).getHost();

        web = new WebView(this);
        setContentView(web);
        terapkanTema();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " AyriApp/" + BuildConfig.VERSION_NAME);
        bersihkanCache();
        pasangJembatan();
        web.setWebViewClient(new Klien());
        web.setWebChromeClient(new Krom());
        web.setDownloadListener((url, ua, cd, mime, panjang) -> {
            if (!url.startsWith("blob:") && !url.startsWith("data:")) bukaLuar(Uri.parse(url));
        });
        if (simpanan != null) web.restoreState(simpanan);
        else web.loadUrl(awal);
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        web.saveState(b);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    /* Terang/gelap HP berubah saat aplikasi terbuka (uiMode ditangani di sini, activity tidak dibuat ulang):
       warna latar dan bar atas/bawah ikut berganti; halaman sendiri membaca prefers-color-scheme dari tema aplikasi (values-night). */
    @Override
    public void onConfigurationChanged(Configuration baru) {
        super.onConfigurationChanged(baru);
        terapkanTema();
    }

    private boolean modeGelap() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void terapkanTema() {
        int latar = getColor(R.color.latar);
        getWindow().setStatusBarColor(latar);
        getWindow().setNavigationBarColor(latar);
        WindowInsetsControllerCompat bar = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bar.setAppearanceLightStatusBars(!modeGelap());
        bar.setAppearanceLightNavigationBars(!modeGelap());
        web.setBackgroundColor(latar);
    }

    /* Hanya HTTPS pada host yang dikonfigurasi untuk flavor ini; HTTP polos dan situs luar tidak dianggap halaman aplikasi. */
    private boolean situsSendiri(Uri u) {
        String h = u == null ? null : u.getHost();
        return u != null && "https".equals(u.getScheme()) && h != null && (h.equals(host) || h.equals("www." + host));
    }

    /* true = sudah ditangani di luar WebView */
    private boolean arahkan(Uri u) {
        if (situsSendiri(u) || "about".equals(u.getScheme())) return false;
        bukaLuar(u);
        return true;
    }

    private void bukaLuar(Uri u) {
        try {
            String sk = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!SKEMA_LUAR.contains(sk)) throw new SecurityException("skema ditolak: " + sk);
            Intent i = "intent".equals(sk) ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, u);
            /* tautan dari halaman tidak boleh menunjuk komponen tertentu (apalagi milik aplikasi ini) atau meminta intent lain lewat selector */
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            i.setSelector(null);
            if (getPackageName().equals(i.getPackage())) throw new SecurityException("tujuan aplikasi sendiri ditolak");
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan ini.", Toast.LENGTH_LONG).show();
        }
    }

    /* berkas sementara (foto kamera, poster yang dibagikan, unduhan) dihapus setelah sehari supaya cache tidak menumpuk */
    private void bersihkanCache() {
        long batas = System.currentTimeMillis() - UMUR_CACHE_MS;
        for (String sub : new String[]{"kamera", "unduhan"}) {
            File[] daftar = new File(getCacheDir(), sub).listFiles();
            if (daftar == null) continue;
            for (File f : daftar) if (f.isFile() && f.lastModified() < batas) f.delete();
        }
    }

    /* Jembatan halaman -> aplikasi. TIDAK memakai addJavascriptInterface (itu disuntikkan ke semua frame, termasuk peta Google di beranda).
       addWebMessageListener hanya memasang objek AyriApp di halaman dengan origin ayrigitar.com, dan pesannya diperiksa lagi di sini. */
    private void pasangJembatan() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        Set<String> origin = new HashSet<>(Arrays.asList("https://" + host, "https://www." + host));
        WebViewCompat.addWebMessageListener(web, "AyriApp", origin, (view, pesan, asal, frameUtama, balas) -> {
            if (frameUtama && situsSendiri(asal) && pesan.getType() == WebMessageCompat.TYPE_STRING) terimaPesan(pesan.getData());
        });
    }

    private static String ambilTeks(JSONObject o, String kunci, String awal) {
        String s = o.optString(kunci, awal);
        return s == null ? awal : s;
    }

    /* nama berkas dan jenis berkas datang dari halaman: dibersihkan dulu sebelum dipakai membuat berkas atau intent */
    private static String namaAman(String nama) {
        String n = nama.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.length() > 100) n = n.substring(n.length() - 100);
        return n.isEmpty() || n.equals(".") || n.equals("..") ? "berkas" : n;
    }

    private static String mimeAman(String mime) {
        return mime != null && mime.matches("[a-z]+/[a-z0-9.+-]+") ? mime : "application/octet-stream";
    }

    private void terimaPesan(String json) {
        if (json == null || json.length() > MAKS_PESAN) return;
        try {
            JSONObject o = new JSONObject(json);
            String op = o.optString("op");
            if ("cetak".equals(op)) {
                String judul = ambilTeks(o, "judul", "Jubeliwatch");
                runOnUiThread(() -> cetakHalaman(judul));
            } else if ("simpanFile".equals(op)) {
                String nama = namaAman(ambilTeks(o, "nama", "unduhan")), mime = mimeAman(ambilTeks(o, "mime", "application/octet-stream"));
                byte[] isi = Base64.decode(o.getString("b64"), Base64.DEFAULT);
                runOnUiThread(() -> simpanBerkas(nama, mime, isi));
            } else if ("cetakStruk".equals(op)) {
                byte[] data = PrinterBt.dariBaris(o.getJSONArray("baris"));
                runOnUiThread(() -> cetakBt(data));
            } else if ("pilihPrinter".equals(op)) {
                runOnUiThread(() -> pilihPrinter(null));
            } else if ("bagikan".equals(op)) {
                String nama = namaAman(ambilTeks(o, "nama", "poster.jpg")), mime = mimeAman(ambilTeks(o, "mime", "image/jpeg")), caption = ambilTeks(o, "teks", "");
                byte[] isi = Base64.decode(o.getString("b64"), Base64.DEFAULT);
                runOnUiThread(() -> bagikanBerkas(nama, mime, isi, caption));
            }
        } catch (Exception e) {
            /* pesan tidak sah: diabaikan */
        }
    }

    private class Klien extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return arahkan(r.getUrl());
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            if (situsSendiri(Uri.parse(url))) v.evaluateJavascript(SKRIP, null);
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!r.isForMainFrame()) return;
            /* alamat yang gagal dimuat ikut ditulis ke halaman ini (yang berjalan dengan origin situs), jadi harus di-escape */
            String alamat = TextUtils.htmlEncode(r.getUrl().toString());
            boolean gelap = modeGelap();
            String latar = gelap ? "#12100E" : "#FAF7F2", teks = gelap ? "#f3e9dc" : "#1C1815";
            String tombol = gelap ? "#E3AC5F" : "#9E5A0C", teksTombol = gelap ? "#15100c" : "#fff";
            String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                    + "<body style='margin:0;display:grid;place-items:center;height:100vh;background:" + latar + ";color:" + teks + ";font-family:sans-serif;text-align:center'>"
                    + "<div style='padding:24px'><h2 style='margin:0 0 8px'>Tidak ada internet</h2>"
                    + "<p style='opacity:.8;margin:0 0 20px'>Buka sekali saat online supaya aplikasi bisa dipakai tanpa internet.</p>"
                    + "<a href='" + alamat + "' style='display:inline-block;padding:12px 22px;border-radius:999px;background:" + tombol + ";color:" + teksTombol + ";font-weight:700;text-decoration:none'>Coba lagi</a></div></body></html>";
            v.loadDataWithBaseURL(awal, html, "text/html", "utf-8", r.getUrl().toString());
        }
    }

    private class Krom extends WebChromeClient {
        /* window.open / target=_blank: situs sendiri dibuka di sini, selain itu (WhatsApp, peta, dll.) di aplikasinya */
        @Override
        public boolean onCreateWindow(WebView v, boolean dialog, boolean pengguna, Message pesan) {
            WebView sementara = new WebView(MainActivity.this);
            sementara.setWebViewClient(new WebViewClient() {
                boolean selesai = false;

                void tangani(WebView vv, Uri u) {
                    if (selesai || u == null || "about".equals(u.getScheme())) return;
                    selesai = true;
                    if (!arahkan(u)) web.loadUrl(u.toString());
                    vv.stopLoading();
                    vv.destroy();
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView vv, WebResourceRequest r) {
                    tangani(vv, r.getUrl());
                    return true;
                }

                @Override
                public void onPageStarted(WebView vv, String url, Bitmap ikon) {
                    tangani(vv, Uri.parse(url));
                }
            });
            ((WebView.WebViewTransport) pesan.obj).setWebView(sementara);
            pesan.sendToTarget();
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest r) {
            runOnUiThread(() -> {
                if (!situsSendiri(r.getOrigin())) { r.deny(); return; }
                List<String> perlu = new ArrayList<>();
                for (String res : r.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) perlu.add(Manifest.permission.RECORD_AUDIO);
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) perlu.add(Manifest.permission.CAMERA);
                }
                List<String> belum = new ArrayList<>();
                for (String p : perlu) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) belum.add(p);
                if (belum.isEmpty()) r.grant(r.getResources());
                else { izinTertunda = r; requestPermissions(belum.toArray(new String[0]), REQ_IZIN); }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            fileParams = p;
            if (mauGambar(p) && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_KAMERA);
                return true;
            }
            bukaPemilih();
            return true;
        }
    }

    private static boolean mauGambar(WebChromeClient.FileChooserParams p) {
        String[] t = p.getAcceptTypes();
        if (t == null || t.length == 0) return true;
        for (String x : t) if (x == null || x.isEmpty() || x.startsWith("image") || x.equals("*/*")) return true;
        return false;
    }

    /* galeri/berkas + kamera (bila diizinkan). Input dengan capture langsung membuka kamera */
    private void bukaPemilih() {
        WebChromeClient.FileChooserParams p = fileParams;
        Intent pilih = p.createIntent();
        if (p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pilih.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        Intent kamera = null;
        fotoKamera = null;
        if (mauGambar(p) && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            File f = new File(new File(getCacheDir(), "kamera"), "foto-" + System.currentTimeMillis() + ".jpg");
            f.getParentFile().mkdirs();
            fotoKamera = FileProvider.getUriForFile(this, getPackageName() + ".berkas", f);
            kamera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, fotoKamera)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        Intent i;
        if (kamera != null && p.isCaptureEnabled()) i = kamera;
        else {
            i = Intent.createChooser(pilih, "Pilih foto");
            if (kamera != null) i.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{kamera});
        }
        try {
            startActivityForResult(i, REQ_FILE);
        } catch (ActivityNotFoundException e) {
            selesaiPilih(null);
        }
    }

    private void selesaiPilih(Uri[] hasil) {
        if (fileCallback != null) fileCallback.onReceiveValue(hasil);
        fileCallback = null;
        fileParams = null;
    }

    @Override
    protected void onActivityResult(int kode, int hasil, Intent data) {
        if (kode != REQ_FILE) { super.onActivityResult(kode, hasil, data); return; }
        if (hasil != RESULT_OK) { selesaiPilih(null); return; }
        Uri[] uri = null;
        if (data != null && data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            uri = new Uri[n];
            for (int i = 0; i < n; i++) uri[i] = data.getClipData().getItemAt(i).getUri();
        } else if (data != null && data.getData() != null) {
            uri = new Uri[]{data.getData()};
        } else if (fotoKamera != null) {
            uri = new Uri[]{fotoKamera};
        }
        selesaiPilih(uri);
    }

    @Override
    public void onRequestPermissionsResult(int kode, String[] izin, int[] hasil) {
        if (kode == REQ_KAMERA) {
            if (fileParams != null) bukaPemilih();
            return;
        }
        if (kode == REQ_BT) {
            Runnable r = tugasBt;
            tugasBt = null;
            if (hasil.length > 0 && hasil[0] == PackageManager.PERMISSION_GRANTED && r != null) denganBt(r);
            else lapor(false, "Izin Bluetooth ditolak. Buka Pengaturan > Aplikasi > Jubeli POS > Izin, lalu izinkan Perangkat sekitar.");
            return;
        }
        if (kode == REQ_IZIN && izinTertunda != null) {
            boolean semua = hasil.length > 0;
            for (int h : hasil) if (h != PackageManager.PERMISSION_GRANTED) semua = false;
            if (semua) izinTertunda.grant(izinTertunda.getResources());
            else izinTertunda.deny();
            izinTertunda = null;
        }
    }

    /* cetak lewat layanan cetak Android (printer Bluetooth/WiFi yang terpasang). Setelah selesai, halaman diberi event afterprint */
    private void cetakHalaman(String judul) {
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        final PrintDocumentAdapter a = web.createPrintDocumentAdapter(judul);
        PrintDocumentAdapter bungkus = new PrintDocumentAdapter() {
            @Override
            public void onStart() { a.onStart(); }

            @Override
            public void onLayout(PrintAttributes lama, PrintAttributes baru, CancellationSignal batal, LayoutResultCallback cb, Bundle ekstra) {
                a.onLayout(lama, baru, batal, cb, ekstra);
            }

            @Override
            public void onWrite(PageRange[] hal, ParcelFileDescriptor tujuan, CancellationSignal batal, WriteResultCallback cb) {
                a.onWrite(hal, tujuan, batal, cb);
            }

            @Override
            public void onFinish() {
                a.onFinish();
                web.evaluateJavascript("window.dispatchEvent(new Event('afterprint'))", null);
            }
        };
        pm.print(judul, bungkus, new PrintAttributes.Builder().build());
    }

    /* ---------- printer struk Bluetooth ----------
       Printer yang dipilih diingat (alamat Bluetooth). Hasil cetak dilaporkan ke halaman lewat event 'jubeli-cetak'. */
    private SharedPreferences pref() { return getSharedPreferences("printer", MODE_PRIVATE); }

    private BluetoothAdapter adapterBt() {
        BluetoothManager m = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    private void lapor(boolean ok, String pesan) {
        runOnUiThread(() -> web.evaluateJavascript("window.dispatchEvent(new CustomEvent('jubeli-cetak',{detail:{ok:" + ok + ",pesan:" + JSONObject.quote(pesan) + "}}))", null));
    }

    /* Android 12+: izin "Perangkat sekitar" diminta sekali; versi lama cukup izin di manifest */
    private void denganBt(Runnable r) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(IZIN_BT) != PackageManager.PERMISSION_GRANTED) {
            tugasBt = r;
            requestPermissions(new String[]{IZIN_BT}, REQ_BT);
            return;
        }
        BluetoothAdapter ad = adapterBt();
        if (ad == null) { lapor(false, "Perangkat ini tidak punya Bluetooth."); return; }
        try {
            if (!ad.isEnabled()) { lapor(false, "Bluetooth mati. Nyalakan Bluetooth, lalu cetak lagi."); return; }
            r.run();
        } catch (SecurityException e) {
            lapor(false, "Aplikasi ini tidak punya izin Bluetooth.");
        }
    }

    private void cetakBt(byte[] data) {
        denganBt(() -> {
            if (pref().getString("alamat", null) == null) pilihPrinter(() -> kirimKePrinter(data));
            else kirimKePrinter(data);
        });
    }

    private void kirimKePrinter(byte[] data) {
        String alamat = pref().getString("alamat", null), nama = pref().getString("nama", "printer");
        BluetoothAdapter ad = adapterBt();
        if (alamat == null || ad == null) return;
        antrianCetak.execute(() -> {
            try {
                PrinterBt.kirim(ad, alamat, data);
                lapor(true, "Struk tercetak di " + nama + ".");
            } catch (Exception e) {
                lapor(false, "Printer " + nama + " tidak tersambung. Pastikan printer menyala dan dekat, lalu cetak lagi. Bila printernya lain, tekan Ganti printer.");
            }
        });
    }

    /* daftar perangkat yang sudah dipasangkan di menu Bluetooth; printer (kelas Imaging) di urutan atas.
       lanjut = cetakan yang menunggu; null = setelah memilih, cetak halaman uji */
    private void pilihPrinter(Runnable lanjut) {
        denganBt(() -> {
            List<BluetoothDevice> daftar = new ArrayList<>(adapterBt().getBondedDevices());
            if (daftar.isEmpty()) {
                lapor(false, "Belum ada printer yang dipasangkan. Pasangkan printer di menu Bluetooth HP dulu.");
                try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception e) { /* abaikan */ }
                return;
            }
            daftar.sort((a, b) -> Boolean.compare(!printer(a), !printer(b)));
            String[] nama = new String[daftar.size()];
            for (int i = 0; i < nama.length; i++) {
                String n = daftar.get(i).getName();
                nama[i] = n == null || n.isEmpty() ? daftar.get(i).getAddress() : n;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Pilih printer struk")
                    .setItems(nama, (d, i) -> {
                        pref().edit().putString("alamat", daftar.get(i).getAddress()).putString("nama", nama[i]).apply();
                        if (lanjut != null) lanjut.run();
                        else kirimKePrinter(halamanUji(nama[i]));
                    })
                    .setNegativeButton("Batal", null)
                    .show();
        });
    }

    private static boolean printer(BluetoothDevice d) {
        BluetoothClass k = d.getBluetoothClass();
        return k != null && k.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING;
    }

    private static byte[] halamanUji(String nama) {
        try {
            JSONArray b = new JSONArray();
            b.put(new JSONObject().put("t", "Jubeli POS").put("a", "c").put("b", true).put("besar", true));
            b.put(new JSONObject().put("t", "Printer siap dipakai").put("a", "c"));
            b.put(new JSONObject().put("t", nama).put("a", "c"));
            return PrinterBt.dariBaris(b);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /* unduhan dari halaman (mis. cadangan data): Android 10+ ke folder Download/AYRI, versi lama lewat menu Bagikan */
    private void simpanBerkas(String nama, String mime, byte[] isi) {
        nama = nama.replaceAll("[\\\\/:*?\"<>|]", "_");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, nama);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Jubeliwatch");
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(isi); }
                Toast.makeText(this, "Tersimpan di Download/Jubeliwatch/" + nama, Toast.LENGTH_LONG).show();
            } else {
                File f = new File(new File(getCacheDir(), "unduhan"), nama);
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(isi); }
                Uri u = FileProvider.getUriForFile(this, getPackageName() + ".berkas", f);
                Intent i = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Simpan " + nama));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Gagal menyimpan berkas: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /* menu Bagikan Android: gambar + teks (WhatsApp, Status, Instagram, Threads, X, dll) */
    private void bagikanBerkas(String nama, String mime, byte[] isi, String teks) {
        nama = nama.replaceAll("[\\/:*?\"<>|]", "_");
        try {
            File f = new File(new File(getCacheDir(), "unduhan"), nama);
            f.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(isi); }
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".berkas", f);
            Intent i = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).putExtra(Intent.EXTRA_TEXT, teks).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("penawaran", teks)); Toast.makeText(this, "Teks & tautan tersalin, tinggal tempel di caption", Toast.LENGTH_SHORT).show(); } catch (Exception e) { }
            startActivity(Intent.createChooser(i, "Bagikan"));
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membagikan: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
