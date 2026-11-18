package io.github.gembirdt_bit.atmossentinel;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String ACTION_PERM = "io.github.gembirdt_bit.atmossentinel.USB_PERMISSION";

    // Boje (iste kao Atmos aplikacije, tamna tema)
    static final int BG = 0xFF0C1512, CARD = 0xFF16251F, CARD2 = 0xFF1D3029, TXT = 0xFFEEF7F3,
            MUTED = 0xFF98B3A8, ACC = 0xFF0F9D78, OK = 0xFF34D399, WARN = 0xFFFBBF24, BAD = 0xFFF87171;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private UsbManager usb;
    private LinearLayout content;
    private TextView status;
    private Button scanBtn;

    private UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface itf;
    private Disk disk;
    private Smart smart;

    private volatile boolean cancel;
    private volatile boolean busy;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_PERM.equals(a)) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) {
                    open(d);
                } else {
                    setStatus("Pristup disku nije odobren. Pritisni „Proveri disk” i dozvoli pristup.", WARN);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && d != null && d.getDeviceId() == device.getDeviceId()) {
                    cancel = true;
                    closeDisk();
                    setStatus("Disk je isključen.", MUTED);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                if (!busy) ui.postDelayed(MainActivity.this::scan, 800);
            }
        }
    };

    // ======================================================================
    // Ekran
    // ======================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, dp(20), p, dp(24));
        sv.addView(root);

        TextView title = text("🛡️ Atmos Sentinel", 24, TXT, true);
        root.addView(title);
        TextView sub = text("Zdravlje HDD i SSD diskova preko USB OTG kabla", 14, MUTED, false);
        sub.setPadding(0, dp(2), 0, dp(14));
        root.addView(sub);

        LinearLayout sc = card();
        status = text("Priključi disk preko OTG kabla, pa pritisni „Proveri disk”.", 15, TXT, false);
        sc.addView(status);
        scanBtn = button("🔍 Proveri disk", true);
        scanBtn.setOnClickListener(v -> scan());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        sc.addView(scanBtn, lp);
        root.addView(sc);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        foot.setPadding(0, dp(18), 0, 0);
        Button coffee = button("☕ Doniraj autoru", false);
        coffee.setTextSize(13);
        coffee.setOnClickListener(v -> donate());
        foot.addView(coffee, new LinearLayout.LayoutParams(-2, -2));
        Button apps = button("📱 Sve Atmos aplikacije", false);
        apps.setTextSize(13);
        apps.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://gembirdt-bit.github.io/atmosaplikacije/"))); }
            catch (Exception ex) { Toast.makeText(this, "Nema pregledača za otvaranje linka", Toast.LENGTH_LONG).show(); }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.topMargin = dp(8);
        foot.addView(apps, alp);
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView vt = text("Atmos Sentinel " + ver, 12, MUTED, false);
        vt.setGravity(Gravity.CENTER);
        vt.setPadding(0, dp(8), 0, 0);
        foot.addView(vt);
        root.addView(foot);

        setContentView(sv);

        IntentFilter f = new IntentFilter(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(rx, f);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
            ui.postDelayed(this::scan, 500);
        }
    }

    @Override
    protected void onDestroy() {
        cancel = true;
        try { unregisterReceiver(rx); } catch (Exception ignored) { }
        worker.execute(this::closeDisk);
        worker.shutdown();
        super.onDestroy();
    }

    // ======================================================================
    // USB
    // ======================================================================

    private static UsbInterface massStorage(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE
                    && f.getInterfaceProtocol() == 0x50) return f;   // Bulk-Only
        }
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) return f;
        }
        return null;
    }

    private void scan() {
        if (busy) return;
        UsbDevice found = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (massStorage(d) != null) { found = d; break; }
        }
        if (found == null) {
            closeDiskAsync();
            content.removeAllViews();
            setStatus("Nije pronađen USB disk.\n\n• Proveri da telefon podržava OTG (neki telefoni traže da se OTG uključi u podešavanjima).\n• 2.5\" diskovima nekad nije dovoljno napajanje sa telefona — koristi Y kabl ili hub sa napajanjem.\n• 3.5\" diskovi uvek traže spoljno napajanje.", WARN);
            return;
        }
        if (!usb.hasPermission(found)) {
            setStatus("Dozvoli aplikaciji pristup disku…", MUTED);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            Intent i = new Intent(ACTION_PERM).setPackage(getPackageName());
            usb.requestPermission(found, PendingIntent.getBroadcast(this, 0, i, fl));
            return;
        }
        open(found);
    }

    private void open(UsbDevice d) {
        if (busy) return;
        busy = true;
        cancel = false;
        scanBtn.setEnabled(false);
        content.removeAllViews();
        setStatus("Čitam podatke sa diska…", MUTED);
        worker.execute(() -> {
            String err = null;
            try {
                closeDisk();
                UsbInterface f = massStorage(d);
                UsbDeviceConnection c = usb.openDevice(d);
                if (c == null || f == null) throw new IOException("Ne mogu da otvorim USB uređaj");
                if (!c.claimInterface(f, true)) { c.close(); throw new IOException("Ne mogu da preuzmem disk od sistema"); }
                device = d; conn = c; itf = f;
                UsbBot bot = new UsbBot(c, f);
                Disk k = new Disk(bot, d.getVendorId(), d.getProductId());
                k.init();
                disk = k;
                Smart s = null;
                if (k.probeAta()) {
                    byte[] data = k.smartData();
                    if (data == null) { k.smartEnable(); data = k.smartData(); }
                    byte[] thr = data != null ? k.smartThresholds() : null;
                    s = Smart.fromAta(k.ataIdentify, data, thr);
                } else if (k.probeNvme()) {
                    s = Smart.fromNvme(k.nvmeIdentify, k.nvmeSmartLog());
                }
                smart = s;
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String fe = err;
            ui.post(() -> {
                busy = false;
                scanBtn.setEnabled(true);
                if (fe != null && disk == null) {
                    setStatus("Greška: " + fe + "\n\nIzvuci i ponovo uključi disk, pa probaj opet.", BAD);
                    return;
                }
                showResult();
            });
        });
    }

    private void closeDiskAsync() { worker.execute(this::closeDisk); }

    private void closeDisk() {
        try {
            if (conn != null) {
                if (itf != null) conn.releaseInterface(itf);
                conn.close();
            }
        } catch (Exception ignored) { }
        conn = null; itf = null; disk = null; device = null;
    }

    // ======================================================================
    // Prikaz rezultata
    // ======================================================================

    private void showResult() {
        content.removeAllViews();
        Disk k = disk;
        Smart s = smart;
        if (k == null) return;

        String name = s != null && !s.model.isEmpty() ? s.model : (k.vendor + " " + k.product).trim();
        if (s == null || !s.smartOk) {
            setStatus(s == null
                    ? "Disk je prepoznat, ali USB kućište (most) ne prosleđuje SMART podatke. Testovi površine i format i dalje rade."
                    : "SMART podaci nisu dostupni.", WARN);
        } else {
            setStatus("Disk je pročitan. Posle provere izvuci i ponovo uključi disk da bi ga telefon ponovo video kao memoriju.", MUTED);
        }

        // --- Zdravlje ---
        LinearLayout hc = card();
        int h = s != null ? s.health : -1;
        int col = h < 0 ? MUTED : h >= 80 ? OK : h >= 50 ? WARN : BAD;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView big = text(h < 0 ? "—" : h + "%", 44, col, true);
        row.addView(big);
        LinearLayout rc = new LinearLayout(this);
        rc.setOrientation(LinearLayout.VERTICAL);
        rc.setPadding(dp(16), 0, 0, 0);
        rc.addView(text("Zdravlje", 13, MUTED, false));
        rc.addView(text(Smart.healthLabel(h), 20, col, true));
        row.addView(rc);
        hc.addView(row);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(Math.max(h, 0));
        pb.setProgressTintList(ColorStateList.valueOf(col));
        pb.setProgressBackgroundTintList(ColorStateList.valueOf(CARD2));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(10));
        pl.topMargin = dp(10);
        pl.bottomMargin = dp(12);
        hc.addView(pb, pl);
        hc.addView(text(name, 17, TXT, true));
        kv(hc, "Vrsta", s != null ? s.kind : "nepoznato");
        kv(hc, "Kapacitet", k.sectors > 0 ? size(k.capacityBytes()) + (k.blockSize != 512 ? " (sektor " + k.blockSize + " B)" : "") : "—");
        if (s != null && !s.serial.isEmpty()) kv(hc, "Serijski broj", s.serial);
        if (s != null && !s.firmware.isEmpty()) kv(hc, "Firmver", s.firmware);
        kv(hc, "USB kućište", (k.bridge.isEmpty() ? "" : k.bridge + " ") + String.format(Locale.ROOT, "%04X:%04X", k.vid, k.pid)
                + (k.sat != 0 ? " · SAT-" + k.sat : k.nvme == Disk.NV_REALTEK ? " · Realtek NVMe" : k.nvme == Disk.NV_ASMEDIA ? " · ASMedia NVMe" : ""));
        content.addView(hc);

        // --- Najvažnije ---
        if (s != null && s.smartOk) {
            LinearLayout mc = card();
            mc.addView(head("Najvažniji parametri"));
            if (s.temperature >= 0) kv(mc, "Temperatura", s.temperature + " °C", s.temperature >= 55 ? WARN : TXT);
            kv(mc, "Vreme rada", Smart.hours(s.powerOnHours));
            if (s.powerCycles >= 0) kv(mc, "Broj uključivanja", String.valueOf(s.powerCycles));
            if (k.type == Disk.T_ATA) {
                kvCount(mc, "Realocirani sektori", s.reallocated, BAD);
                kvCount(mc, "Nestabilni sektori", s.pending, BAD);
                kvCount(mc, "Nepopravljivi sektori", s.uncorrectable, BAD);
                kvCount(mc, "CRC greške (kabl)", s.crc, WARN);
                if (s.lifeLeft >= 0) kv(mc, "Preostali vek SSD-a", s.lifeLeft + "%", s.lifeLeft < 30 ? WARN : TXT);
            } else {
                kv(mc, "Preostali vek", s.lifeLeft + "% (potrošeno " + s.nvmeUsed + "%)", s.lifeLeft < 30 ? WARN : TXT);
                kv(mc, "Rezervni prostor", s.nvmeSpare + "% (prag " + s.nvmeSpareThr + "%)", s.nvmeSpare <= s.nvmeSpareThr ? BAD : TXT);
                kvCount(mc, "Greške medija", s.nvmeMediaErrors, BAD);
                kv(mc, "Upisano", size(s.nvmeWritten * 512000L));
                kv(mc, "Pročitano", size(s.nvmeRead * 512000L));
                kv(mc, "Nebezbedna gašenja", String.valueOf(s.nvmeUnsafe));
            }
            content.addView(mc);
        }

        // --- Napomene ---
        if (s != null && !s.notes.isEmpty()) {
            LinearLayout nc = card();
            nc.addView(head("Šta to znači"));
            for (String n : s.notes) {
                TextView t = text("• " + n, 14, TXT, false);
                t.setPadding(0, dp(3), 0, dp(3));
                nc.addView(t);
            }
            content.addView(nc);
        }

        // --- Testovi ---
        LinearLayout tc = card();
        tc.addView(head("Provera ispravnosti"));
        tc.addView(text("Test čitanja ne menja podatke. Test upisa i format brišu sve sa diska.", 13, MUTED, false));
        Button q = button("🧪 Brzi test čitanja (~2 min)", false);
        q.setOnClickListener(v -> surfaceTest(false));
        Button full = button("🔬 Pun test čitanja (ceo disk)", false);
        full.setOnClickListener(v -> surfaceTest(true));
        Button fmt = button("🧹 Test upisa + brzi format", false);
        fmt.setTextColor(WARN);
        fmt.setOnClickListener(v -> confirmFormat());
        for (Button bt : new Button[]{q, full, fmt}) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
            l.topMargin = dp(8);
            tc.addView(bt, l);
        }
        content.addView(tc);

        // --- SMART tabela ---
        if (s != null && s.smartOk && !s.attrs.isEmpty()) {
            LinearLayout ac = card();
            ac.addView(head("SMART atributi"));
            HorizontalScrollView hs = new HorizontalScrollView(this);
            LinearLayout tbl = new LinearLayout(this);
            tbl.setOrientation(LinearLayout.VERTICAL);
            tbl.addView(attrRow("ID", "Naziv", "Vred.", "Najg.", "Prag", "Sirova vrednost", MUTED, true));
            for (Smart.Attr a : s.attrs) {
                int c = a.failing ? BAD : a.critical ? (a.id == 199 ? WARN : BAD) : TXT;
                tbl.addView(attrRow(String.format(Locale.ROOT, "%02X", a.id), a.name(),
                        String.valueOf(a.value), String.valueOf(a.worst),
                        a.threshold >= 0 ? String.valueOf(a.threshold) : "—", Smart.rawText(a), c, false));
            }
            hs.addView(tbl);
            ac.addView(hs);
            content.addView(ac);
        }

        Button share = button("📤 Podeli izveštaj", false);
        share.setOnClickListener(v -> shareReport());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(12);
        content.addView(share, sl);
    }

    private View attrRow(String id, String name, String v, String w, String t, String raw, int color, boolean header) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(5), 0, dp(5));
        int[] widths = {36, 210, 48, 48, 44, 150};
        String[] vals = {id, name, v, w, t, raw};
        for (int i = 0; i < vals.length; i++) {
            TextView x = text(vals[i], 13, color, header);
            if (i != 1 && i != 5) x.setGravity(Gravity.END);
            x.setPadding(i == 0 ? 0 : dp(6), 0, 0, 0);
            if (!header && i == 0) x.setTypeface(Typeface.MONOSPACE);
            r.addView(x, new LinearLayout.LayoutParams(dp(widths[i]), -2));
        }
        return r;
    }

    // ======================================================================
    // Test čitanja (ne briše podatke)
    // ======================================================================

    private TextView tStat;
    private SurfaceMap tMap;
    private Button tCancel;
    private LinearLayout tCard;

    private void testCard(String title, int cells) {
        if (tCard != null) content.removeView(tCard);
        tCard = card();
        tCard.addView(head(title));
        tMap = new SurfaceMap(this);
        tMap.reset(cells);
        tCard.addView(tMap, new LinearLayout.LayoutParams(-1, -2));
        tStat = text("", 14, TXT, false);
        tStat.setPadding(0, dp(10), 0, 0);
        tCard.addView(tStat);
        tCancel = button("Prekini", false);
        tCancel.setOnClickListener(v -> { cancel = true; tCancel.setEnabled(false); });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = dp(10);
        tCard.addView(tCancel, l);
        content.addView(tCard, 0);
    }

    private void startTest() {
        busy = true;
        cancel = false;
        scanBtn.setEnabled(false);
    }

    private void endTest(String msg, int color) {
        busy = false;
        scanBtn.setEnabled(true);
        if (tCancel != null) tCancel.setVisibility(View.GONE);
        if (tStat != null) { tStat.setText(msg); tStat.setTextColor(color); }
    }

    private void surfaceTest(boolean full) {
        if (busy) return;
        final Disk k = disk;
        if (k == null || k.sectors <= 0) { toast("Disk nije spreman — pritisni „Proveri disk”."); return; }
        final int cells = 800;
        testCard(full ? "Pun test čitanja" : "Brzi test čitanja", cells);
        startTest();
        worker.execute(() -> {
            final int bs = k.blockSize;
            final int blk = Math.max(1, (full ? 1024 * 1024 : 64 * 1024) / bs);
            byte[] buf = new byte[blk * bs];
            long total = k.sectors;
            long steps = full ? (total + blk - 1) / blk : 2000;
            long ok = 0, slow = 0, bad = 0;
            List<Long> badLba = new ArrayList<>();
            long t0 = SystemClock.elapsedRealtime(), lastUi = 0, bytes = 0;
            String err = null;
            try {
                for (long i = 0; i < steps && !cancel; i++) {
                    long lba = full ? i * blk : (total - blk) * i / (steps - 1);
                    int n = (int) Math.min(blk, total - lba);
                    if (n <= 0) break;
                    long s0 = SystemClock.elapsedRealtime();
                    boolean good = k.read(lba, n, buf);
                    long dt = SystemClock.elapsedRealtime() - s0;
                    if (!good && !full) { good = k.read(lba, n, buf); }
                    byte st;
                    if (!good) { bad++; st = SurfaceMap.BAD; if (badLba.size() < 20) badLba.add(lba); }
                    else if (dt > (full ? 3000 : 1000) && i > 0) { slow++; st = SurfaceMap.SLOW; }
                    else { ok++; st = SurfaceMap.OK; }
                    bytes += (long) n * bs;
                    final int cell = (int) (lba * cells / total);
                    final byte fst = st;
                    long now = SystemClock.elapsedRealtime();
                    final long fi = i + 1, fbad = bad, fslow = slow, fbytes = bytes;
                    final double el = (now - t0) / 1000.0;
                    ui.post(() -> tMap.set(cell, fst));
                    if (now - lastUi > 400 || fi == steps) {
                        lastUi = now;
                        ui.post(() -> {
                            tMap.invalidate();
                            double pct = fi * 100.0 / steps;
                            double mbs = el > 0 ? fbytes / el / 1e6 : 0;
                            String eta = pct > 0.5 ? " · još ~" + dur((long) (el * (100 - pct) / pct)) : "";
                            tStat.setText(String.format(Locale.ROOT, "%.1f%% · %.1f MB/s%s\nGreške: %d · Sporo: %d", pct, mbs, eta, fbad, fslow));
                        });
                    }
                }
            } catch (IOException e) {
                err = e.getMessage();
            }
            final long fok = ok, fslow = slow, fbad = bad;
            final String fe = err;
            final double el = (SystemClock.elapsedRealtime() - t0) / 1000.0;
            final double mbs = el > 0 ? bytes / el / 1e6 : 0;
            ui.post(() -> {
                tMap.invalidate();
                StringBuilder sb = new StringBuilder();
                if (fe != null) sb.append("Test je prekinut: ").append(fe).append("\n");
                else if (cancel) sb.append("Test je prekinut.\n");
                sb.append(String.format(Locale.ROOT, "Pročitano bez greške: %d · Sporo: %d · Greške: %d\nProsečna brzina: %.1f MB/s · Trajanje: %s", fok, fslow, fbad, mbs, dur((long) el)));
                if (!badLba.isEmpty()) {
                    sb.append("\nLoši blokovi (LBA): ");
                    for (int i = 0; i < badLba.size(); i++) sb.append(i > 0 ? ", " : "").append(badLba.get(i));
                }
                int c;
                if (fbad > 0) { sb.append("\n\n❗ Disk ima delove koji se ne mogu pročitati."); c = BAD; }
                else if (fslow > 5) { sb.append("\n\n⚠️ Ima sporih delova — moguć početak problema."); c = WARN; }
                else if (fe == null && !cancel) { sb.append("\n\n✔ Svi provereni delovi diska se čitaju ispravno."); c = OK; }
                else c = TXT;
                endTest(sb.toString(), c);
            });
        });
    }

    // ======================================================================
    // Test upisa + brzi format (BRIŠE SVE)
    // ======================================================================

    private void confirmFormat() {
        if (busy) return;
        if (disk == null || disk.sectors <= 0) { toast("Disk nije spreman — pritisni „Proveri disk”."); return; }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), 0);
        TextView m = text("Upisuje se probni sadržaj na 500 mesta po celom disku, čita nazad i upoređuje. Ako je sve ispravno, disk se formatira (FAT32, jedna particija).\n\nSVI PODACI NA DISKU BIĆE OBRISANI.", 14, TXT, false);
        l.addView(m);
        TextView ll = text("Naziv diska", 13, MUTED, false);
        ll.setPadding(0, dp(12), 0, 0);
        l.addView(ll);
        EditText label = new EditText(this);
        label.setText("ATMOS");
        label.setTextColor(TXT);
        label.setSingleLine(true);
        label.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        l.addView(label);
        CheckBox cb = new CheckBox(this);
        cb.setText("Razumem da će svi podaci biti obrisani");
        cb.setTextColor(TXT);
        cb.setButtonTintList(ColorStateList.valueOf(ACC));
        l.addView(cb);
        String name = smart != null && !smart.model.isEmpty() ? smart.model : (disk.vendor + " " + disk.product).trim();
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Obrisati " + name + " (" + size(disk.capacityBytes()) + ")?")
                .setView(l)
                .setNegativeButton("Odustani", null)
                .setPositiveButton("Obriši i formatiraj", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!cb.isChecked()) { toast("Potvrdi da razumeš da se podaci brišu."); return; }
            String lab = label.getText().toString().trim();
            if (lab.isEmpty()) lab = "ATMOS";
            d.dismiss();
            writeTestAndFormat(lab);
        }));
        d.show();
    }

    private void writeTestAndFormat(String label) {
        final Disk k = disk;
        if (k == null) return;
        final int points = 500;
        testCard("Test upisa + brzi format", points);
        startTest();
        worker.execute(() -> {
            final int bs = k.blockSize;
            final int blk = Math.max(1, (64 * 1024) / bs);
            final long total = k.sectors;
            byte[] pat = new byte[blk * bs];
            byte[] back = new byte[blk * bs];
            Random rnd = new Random();
            long bad = 0, slow = 0;
            String err = null;
            boolean formatted = false;
            Fat32 fat = new Fat32();
            long t0 = SystemClock.elapsedRealtime();
            try {
                for (int i = 0; i < points && !cancel; i++) {
                    long lba = (total - blk) * i / (points - 1);
                    rnd.nextBytes(pat);
                    for (int j = 0; j < 8; j++) pat[j] = (byte) (lba >>> (8 * j));
                    long s0 = SystemClock.elapsedRealtime();
                    boolean good = k.write(lba, blk, pat);
                    if (!good && k.last != null && k.last.senseKey == 7) throw new IOException("Disk je zaštićen od upisa");
                    if (good) {
                        Arrays.fill(back, (byte) 0);
                        good = k.read(lba, blk, back) && Arrays.equals(pat, back);
                    }
                    long dt = SystemClock.elapsedRealtime() - s0;
                    byte st = !good ? SurfaceMap.BAD : dt > 2000 ? SurfaceMap.SLOW : SurfaceMap.OK;
                    if (!good) bad++;
                    else if (st == SurfaceMap.SLOW) slow++;
                    final int fi = i;
                    final byte fst = st;
                    final long fbad = bad, fslow = slow;
                    ui.post(() -> {
                        tMap.set(fi, fst);
                        tMap.invalidate();
                        tStat.setText(String.format(Locale.ROOT, "Test upisa: %d / %d\nGreške: %d · Sporo: %d", fi + 1, points, fbad, fslow));
                    });
                }
                if (!cancel && bad == 0) {
                    ui.post(() -> tCancel.setVisibility(View.GONE));
                    fat.format(k, label, (pct, msg) -> ui.post(() -> tStat.setText("Test upisa: ispravno ✔\n" + msg + " " + pct + "%")));
                    formatted = true;
                }
            } catch (IOException e) {
                err = e.getMessage();
            }
            final long fbad = bad, fslow = slow;
            final String fe = err;
            final boolean ff = formatted;
            final String fi = fat.info;
            final long secs = (SystemClock.elapsedRealtime() - t0) / 1000;
            ui.post(() -> {
                StringBuilder sb = new StringBuilder();
                int c;
                if (fe != null) { sb.append("Greška: ").append(fe); c = BAD; }
                else if (cancel && !ff) { sb.append("Prekinuto. Disk NIJE formatiran, a deo podataka je već prepisan."); c = WARN; }
                else if (fbad > 0) {
                    sb.append("❗ ").append(fbad).append(" od ").append(points).append(" mesta nije prošlo proveru upisa/čitanja.\nDisk nije pouzdan i NIJE formatiran.");
                    c = BAD;
                } else {
                    sb.append("✔ Svih ").append(points).append(" probnih upisa je ispravno pročitano.");
                    if (fslow > 5) sb.append("\n⚠️ Sporih mesta: ").append(fslow);
                    if (ff) sb.append("\n✔ Disk je formatiran: ").append(fi).append("\n\nIzvuci i ponovo uključi disk da bi ga telefon video.");
                    c = OK;
                }
                sb.append("\nTrajanje: ").append(dur(secs));
                endTest(sb.toString(), c);
            });
        });
    }

    // ======================================================================
    // Izveštaj
    // ======================================================================

    private void shareReport() {
        Disk k = disk;
        Smart s = smart;
        if (k == null) return;
        StringBuilder r = new StringBuilder();
        r.append("Atmos Sentinel — izveštaj o disku\n\n");
        r.append("Disk: ").append(s != null && !s.model.isEmpty() ? s.model : (k.vendor + " " + k.product).trim()).append('\n');
        if (s != null) {
            r.append("Vrsta: ").append(s.kind).append('\n');
            if (!s.serial.isEmpty()) r.append("Serijski broj: ").append(s.serial).append('\n');
            if (!s.firmware.isEmpty()) r.append("Firmver: ").append(s.firmware).append('\n');
        }
        r.append("Kapacitet: ").append(size(k.capacityBytes())).append('\n');
        if (s != null && s.smartOk) {
            r.append("\nZdravlje: ").append(s.health).append("% — ").append(Smart.healthLabel(s.health)).append('\n');
            if (s.temperature >= 0) r.append("Temperatura: ").append(s.temperature).append(" °C\n");
            r.append("Vreme rada: ").append(Smart.hours(s.powerOnHours)).append('\n');
            if (s.powerCycles >= 0) r.append("Broj uključivanja: ").append(s.powerCycles).append('\n');
            if (k.type == Disk.T_ATA) {
                if (s.reallocated >= 0) r.append("Realocirani sektori: ").append(s.reallocated).append('\n');
                if (s.pending >= 0) r.append("Nestabilni sektori: ").append(s.pending).append('\n');
                if (s.uncorrectable >= 0) r.append("Nepopravljivi sektori: ").append(s.uncorrectable).append('\n');
                if (s.crc >= 0) r.append("CRC greške: ").append(s.crc).append('\n');
                if (s.lifeLeft >= 0) r.append("Preostali vek SSD-a: ").append(s.lifeLeft).append("%\n");
            } else {
                r.append("Potrošeno: ").append(s.nvmeUsed).append("%\n");
                r.append("Greške medija: ").append(s.nvmeMediaErrors).append('\n');
                r.append("Upisano: ").append(size(s.nvmeWritten * 512000L)).append('\n');
            }
            r.append('\n');
            for (String n : s.notes) r.append("• ").append(n).append('\n');
            if (!s.attrs.isEmpty()) {
                r.append("\nSMART atributi (ID  vrednost/najgora/prag  sirova  naziv):\n");
                for (Smart.Attr a : s.attrs) {
                    r.append(String.format(Locale.ROOT, "%02X  %3d/%3d/%3s  %s  %s\n", a.id, a.value, a.worst,
                            a.threshold >= 0 ? String.valueOf(a.threshold) : "-", Smart.rawText(a), a.name()));
                }
            }
        } else {
            r.append("\nSMART podaci nisu dostupni preko ovog USB kućišta.\n");
        }
        if (tStat != null && tStat.getText().length() > 0) r.append("\nPoslednji test:\n").append(tStat.getText()).append('\n');
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Atmos Sentinel — izveštaj o disku");
        i.putExtra(Intent.EXTRA_TEXT, r.toString());
        startActivity(Intent.createChooser(i, "Podeli izveštaj"));
    }

    // ======================================================================
    // Doniraj autoru
    // ======================================================================

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void donate() {
        Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        WebView wv = new WebView(this);
        wv.setBackgroundColor(Color.TRANSPARENT);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void copy(String t) {
                ui.post(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Račun", t));
                });
            }

            @JavascriptInterface
            public void close() { ui.post(d::dismiss); }
        }, "Atmos");
        wv.loadUrl("file:///android_asset/donate.html");
        d.setContentView(wv, new ViewGroup.LayoutParams(-1, -1));
        d.show();
    }

    // ======================================================================
    // Pomoćne
    // ======================================================================

    private void setStatus(String s, int color) {
        ui.post(() -> { status.setText(s); status.setTextColor(color == MUTED ? TXT : color); });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView head(String s) {
        TextView t = text(s, 16, TXT, true);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        g.setStroke(1, 0x1AFFFFFF);
        c.setBackground(g);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.bottomMargin = dp(12);
        c.setLayoutParams(l);
        return c;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(primary ? Color.WHITE : TXT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACC : CARD2);
        g.setCornerRadius(dp(12));
        if (!primary) g.setStroke(1, 0x33FFFFFF);
        b.setBackground(g);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setStateListAnimator(null);
        return b;
    }

    private void kv(LinearLayout parent, String k, String v) { kv(parent, k, v, TXT); }

    private void kv(LinearLayout parent, String k, String v, int color) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(4), 0, dp(4));
        TextView a = text(k, 14, MUTED, false);
        TextView b = text(v, 14, color, color != TXT);
        b.setGravity(Gravity.END);
        r.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(b, new LinearLayout.LayoutParams(0, -2, 1.3f));
        parent.addView(r);
    }

    private void kvCount(LinearLayout parent, String k, long v, int badColor) {
        if (v < 0) return;
        kv(parent, k, String.valueOf(v), v > 0 ? badColor : OK);
    }

    static String size(long bytes) {
        if (bytes <= 0) return "0 B";
        double b = bytes;
        if (b >= 1e12) return String.format(Locale.ROOT, "%.2f TB", b / 1e12);
        if (b >= 1e9) return String.format(Locale.ROOT, "%.1f GB", b / 1e9);
        if (b >= 1e6) return String.format(Locale.ROOT, "%.0f MB", b / 1e6);
        return String.format(Locale.ROOT, "%.0f KB", b / 1e3);
    }

    static String dur(long s) {
        if (s < 60) return s + " s";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " s";
        return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }
}
