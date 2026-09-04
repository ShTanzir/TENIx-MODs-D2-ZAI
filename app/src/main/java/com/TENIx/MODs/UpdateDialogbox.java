package com.TENIx.MODs;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * TENIx MODs — UPDATE DIALOG BOX (V2)
 *
 * V2 ENGINE:
 *  1. Instant check on every app launch (hook).
 *  2. LIVE POLLING every 15s while the app process is alive → admin presses
 *     "CALL UPDATE" → notification + dialog appear within seconds. NO restart needed.
 *  3. Every CALL UPDATE gets a fresh "calledAt" timestamp → EVERY press fires a
 *     NEW notification (unique ID) — fixes "worked 3 times then stopped".
 *  4. Notification image: forces HTTPS, downloads bitmap, sets LargeIcon +
 *     BigPictureStyle. Waits for Android 13 notification permission (4 retries).
 *
 * Hook: invoke-static {p0}, Lcom/TENIx/MODs/UpdateDialogbox;->show(Landroid/app/Activity;)V
 */
public class UpdateDialogbox {

    /* ==== CONFIG — search & replace in dex strings after build ================
       REPLACE_FIREBASE_REALTIME_DB_URL -> https://your-project-default-rtdb.firebaseio.com/
       REPLACE_FIREBASE_DB_SECRET       -> legacy database secret (or leave placeholder) */
    public static String FIREBASE_DB_URL    = "REPLACE_FIREBASE_REALTIME_DB_URL";
    public static String FIREBASE_DB_SECRET = "REPLACE_FIREBASE_DB_SECRET";
    /* ========================================================================== */

    private static final int    PURPLE        = 0xFF7C3AED;
    private static final long   POLL_MS       = 15000;   // live check every 15s
    private static final String PREFS         = "TENIx_MODS_UPDATES";
    private static final String KEY_NOTIFIED  = "last_notified_calledAt";
    private static final int    BASE_NOTIF_ID = 7331;

    private static final Object LOCK = new Object();
    private static Context appContext;
    private static WeakReference<Activity> liveActivity = new WeakReference<Activity>(null);
    private static HandlerThread pollThread;
    private static Handler pollHandler;
    private static boolean polling = false;
    private static boolean dialogShowing = false;
    private static String  sessionDialogTs = "";
    private static boolean permRequested = false;

    /** HOOK ENTRY POINT */
    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try { liveActivity = new WeakReference<Activity>(activity); } catch (Throwable ignored) {}
        try { appContext = activity.getApplicationContext(); } catch (Throwable ignored) {}
        runCheck(activity);     // instant check on launch
        startPolling();         // live updates while app is running
    }

    /* ===================== polling engine (V2) ===================== */

    private static void runCheck(final Activity act) {
        new Thread(new Runnable() {
            @Override public void run() { try { checkNow(act); } catch (Throwable ignored) {} }
        }).start();
    }

    private static void startPolling() {
        synchronized (LOCK) {
            if (polling || appContext == null) return;
            String u = (FIREBASE_DB_URL == null) ? "" : FIREBASE_DB_URL.trim();
            if (u.isEmpty() || u.contains("REPLACE_")) return;
            try {
                pollThread = new HandlerThread("TENIx_Update_Poll");
                pollThread.setPriority(Thread.MIN_PRIORITY);
                pollThread.start();
                pollHandler = new Handler(pollThread.getLooper());
                polling = true;
                pollHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        try { checkNow(null); } catch (Throwable ignored) {}
                        Handler h = pollHandler;
                        if (h != null) h.postDelayed(this, POLL_MS);
                    }
                }, POLL_MS);
            } catch (Throwable t) { polling = false; }
        }
    }

    /* ===================== firebase check (REST) ===================== */

    private static void checkNow(final Activity actFromCall) {
        try {
            String base = (FIREBASE_DB_URL == null) ? "" : FIREBASE_DB_URL.trim();
            if (base.isEmpty() || base.contains("REPLACE_")) return;
            if (!base.endsWith("/")) base = base + "/";

            Context ctx = (appContext != null) ? appContext : actFromCall;
            if (ctx == null) return;
            String key = ctx.getPackageName().replace('.', '_');
            String urlStr = base + "apps/" + key + ".json";

            String secret = (FIREBASE_DB_SECRET == null) ? "" : FIREBASE_DB_SECRET.trim();
            if (!secret.isEmpty() && !secret.contains("REPLACE_")) {
                urlStr = urlStr + "?auth=" + URLEncoder.encode(secret, "UTF-8");
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "TENIxMODs/2.0");
            int code = conn.getResponseCode();
            InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String body = readStream(in);
            try { conn.disconnect(); } catch (Throwable ignored) {}

            if (body == null || body.length() < 2 || "null".equals(body)) return;

            JSONObject root = new JSONObject(body);
            JSONObject up = root.optJSONObject("update");
            if (up == null) up = root;

            boolean active = up.optBoolean("active", false) || up.optBoolean("updateActive", false);
            if (!active) return;

            long calledAtL = up.optLong("calledAt", 0L);
            final String ts = String.valueOf(calledAtL);

            final String version  = up.optString("version", "").trim();
            final String date     = up.optString("releaseDate", up.optString("date", "")).trim();
            final String desc     = up.optString("description", "").trim();
            final String features = up.optString("features", "").trim();
            final String link     = up.optString("updateUrl", up.optString("url", "")).trim();

            String imgUrl = up.optString("imageUrl", "").trim();
            if (imgUrl.startsWith("http://")) imgUrl = "https://" + imgUrl.substring(7); // FIX: cleartext block
            final Bitmap img = imgUrl.isEmpty() ? null : downloadBitmap(imgUrl);

            final boolean isNewUpdate = !ts.equals(getPref(KEY_NOTIFIED, ""));

            postToMain(new Runnable() {
                @Override public void run() {
                    try {
                        Activity a = (actFromCall != null) ? actFromCall : liveActivity.get();

                        /* NOTIFICATION — only for NEW calledAt (every CALL UPDATE press) */
                        if (isNewUpdate) {
                            notifyNewUpdate(a, version, desc, img, ts);
                            setPref(KEY_NOTIFIED, ts);
                        }

                        /* DIALOG — every launch while active; once per calledAt per session */
                        if (a != null && !a.isFinishing() && !a.isDestroyed()
                                && !dialogShowing && !sessionDialogTs.equals(ts)) {
                            sessionDialogTs = ts;
                            showUpdateDialog(a, version, date, desc, features, link);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) { /* never crash the host app */ }
    }

    /* ===================== notification (V2: permission-aware + image) ===================== */

    private static void notifyNewUpdate(final Activity act, final String version,
                                        final String desc, final Bitmap img, final String ts) {
        try {
            Context ctx = (act != null) ? act : appContext;
            if (ctx == null) return;
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            boolean enabled = true;
            if (Build.VERSION.SDK_INT >= 24) enabled = nm.areNotificationsEnabled();

            if (!enabled) {
                if (act != null && Build.VERSION.SDK_INT >= 33) {
                    if (!permRequested) {           // ask once per process
                        permRequested = true;
                        try {
                            act.requestPermissions(
                                    new String[]{"android.permission.POST_NOTIFICATIONS"}, BASE_NOTIF_ID);
                        } catch (Throwable ignored) {}
                    }
                    retryNotify(act, version, desc, img, ts, 1);   // wait for user to Allow
                }
                return;
            }
            postNotification(ctx, nm, version, desc, img, ts);
        } catch (Throwable ignored) {}
    }

    private static void retryNotify(final Activity act, final String version, final String desc,
                                    final Bitmap img, final String ts, final int attempt) {
        if (attempt > 4) return;
        postDelayedMain(new Runnable() {
            @Override public void run() {
                try {
                    Context ctx = (act != null) ? act : appContext;
                    if (ctx == null) return;
                    NotificationManager nm = (NotificationManager)
                            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm == null) return;
                    boolean enabled = Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled();
                    if (enabled) postNotification(ctx, nm, version, desc, img, ts);
                    else retryNotify(act, version, desc, img, ts, attempt + 1);
                } catch (Throwable ignored) {}
            }
        }, 1200L * attempt);
    }

    private static void postNotification(Context ctx, NotificationManager nm, String version,
                                         String desc, Bitmap img, String ts) {
        try {
            String text = (desc == null || desc.trim().isEmpty())
                    ? "A new version is ready — update now!" : desc.trim();
            String title = "New Update"
                    + ((version == null || version.trim().isEmpty()) ? "" : " • v" + version.trim());

            /* use the HOST APP's own icon as the small icon */
            int smallIcon = android.R.drawable.stat_notify_more;
            try {
                ApplicationInfo ai = ctx.getApplicationInfo();
                if (ai != null && ai.icon != 0) smallIcon = ai.icon;
            } catch (Throwable ignored) {}

            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("TENIx_Update_Channel",
                        "App Updates", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Update alerts from TENIx MODs");
                ch.enableLights(true);
                ch.setLightColor(PURPLE);
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }

            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(ctx, "TENIx_Update_Channel")
                    : new Notification.Builder(ctx);

            b.setSmallIcon(smallIcon)
             .setContentTitle(title)
             .setContentText(text)
             .setAutoCancel(true);

            if (img != null) {
                try { b.setLargeIcon(Bitmap.createScaledBitmap(img, 128, 128, true)); }
                catch (Throwable ignored) {}
                b.setStyle(new Notification.BigPictureStyle()
                        .bigPicture(img)
                        .bigLargeIcon((Bitmap) null)
                        .setBigContentTitle(title)
                        .setSummaryText(text));
            } else {
                b.setStyle(new Notification.BigTextStyle().bigText(text));
            }

            /* UNIQUE ID per update → every CALL UPDATE shows a new notification */
            int nid = BASE_NOTIF_ID + (Math.abs(ts.hashCode()) % 50000);
            nm.notify(nid, b.build());
        } catch (Throwable ignored) {}
    }

    /* ===================== dialog UI (same premium spec) ===================== */

    private static void showUpdateDialog(final Activity act, String version, String date,
                                         String desc, String features, final String updateUrl) {
        if (dialogShowing) return;
        dialogShowing = true;

        boolean dark = isDarkMode(act);
        int bg   = dark ? 0xFF0F0F14 : 0xFFFFFFFF;
        int text = dark ? 0xFFF3F3F5 : 0xFF141418;
        int sub  = dark ? 0xFF9A9AA5 : 0xFF5A5A66;
        int line = dark ? 0x2AFFFFFF : 0x1A141418;

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(bg);
        cardBg.setCornerRadius(dp(act, 28));
        cardBg.setStroke(dp(act, 2), PURPLE);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(cardBg);
        root.setMinimumHeight(dp(act, 360));

        /* header: New Update + logo */
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), 0);

        TextView title = new TextView(act);
        title.setText("New Update");
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout logoSlot = new FrameLayout(act);
        GradientDrawable slotBg = new GradientDrawable();
        slotBg.setColor((PURPLE & 0x00FFFFFF) | 0x1A000000);
        slotBg.setCornerRadius(dp(act, 14));
        slotBg.setStroke(dp(act, 1), (PURPLE & 0x00FFFFFF) | 0x40000000);
        logoSlot.setBackground(slotBg);
        logoSlot.addView(makeLogoView(act, loadLogo(act)),
                new FrameLayout.LayoutParams(dp(act, 40), dp(act, 40), Gravity.CENTER));
        header.addView(logoSlot, new LinearLayout.LayoutParams(dp(act, 56), dp(act, 56)));

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* version */
        TextView v = new TextView(act);
        v.setText((version == null || version.isEmpty()) ? "Version —" : "Version " + version);
        v.setTextColor(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(v, sectionLabel(act, 14));

        /* release date */
        TextView d = new TextView(act);
        d.setText((date == null || date.isEmpty()) ? "Release —" : "Release " + date);
        d.setTextColor(sub);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(d, sectionLabel(act, 8));

        /* divider */
        View divider = new View(act);
        divider.setBackgroundColor(line);
        LinearLayout.LayoutParams dvp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1));
        dvp.leftMargin = dp(act, 16); dvp.rightMargin = dp(act, 16); dvp.topMargin = dp(act, 14);
        root.addView(divider, dvp);

        /* what's new */
        TextView wn = new TextView(act);
        wn.setText("What's New");
        wn.setTextColor(text);
        wn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        wn.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(wn, sectionLabel(act, 14));

        /* feature list */
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        int count = 0;
        if (features != null && !features.isEmpty()) {
            String[] lines = features.split("\n");
            for (String raw : lines) {
                String l = raw.trim();
                if (l.startsWith("✦")) l = l.substring(1).trim();
                if (l.isEmpty()) continue;
                list.addView(featureRow(act, l, text));
                count++;
            }
        }
        if (count == 0) {
            String fb = (desc != null && !desc.isEmpty()) ? desc : "Bug fixes and improvements";
            list.addView(featureRow(act, fb, text));
        }
        ScrollView scroller = new ScrollView(act);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.leftMargin = dp(act, 16); slp.rightMargin = dp(act, 16); slp.topMargin = dp(act, 6);
        root.addView(scroller, slp);

        /* buttons */
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView exitB = pillButton(act, "EXIT", false);
        TextView updB  = pillButton(act, "UPDATE", true);

        row.addView(exitB, new LinearLayout.LayoutParams(0, dp(act, 44), 1f));
        row.addView(new View(act), new LinearLayout.LayoutParams(dp(act, 12), 1));
        row.addView(updB,  new LinearLayout.LayoutParams(0, dp(act, 44), 1f));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(act, 16); blp.rightMargin = dp(act, 16);
        blp.topMargin = dp(act, 14);  blp.bottomMargin = dp(act, 20);
        root.addView(row, blp);

        exitB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { exitApp(act); }
        });
        updB.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (updateUrl == null || updateUrl.trim().isEmpty()) {
                    Toast.makeText(act, "Update link not set by admin", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl.trim())));
                } catch (Throwable t) {
                    Toast.makeText(act, "No browser found", Toast.LENGTH_SHORT).show();
                }
            }
        });

        Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) { dialogShowing = false; }
        });
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(dp(act, 320), dp(act, 360));
            w.setGravity(Gravity.CENTER);
        }
        dialog.show();
    }

    /* ============================ helpers ============================ */

    private static LinearLayout.LayoutParams sectionLabel(Activity a, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(a, 16); lp.rightMargin = dp(a, 16); lp.topMargin = dp(a, topMargin);
        return lp;
    }

    private static TextView featureRow(Context c, String lineText, int textColor) {
        TextView tv = new TextView(c);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        int s = ssb.length();
        ssb.append("✦");
        ssb.setSpan(new ForegroundColorSpan(PURPLE), s, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new StyleSpan(Typeface.BOLD), s, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.append("  ");
        int t = ssb.length();
        ssb.append(lineText);
        ssb.setSpan(new ForegroundColorSpan(textColor), t, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(ssb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 5);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static TextView pillButton(Activity a, String label, boolean filled) {
        TextView b = new TextView(a);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(a, 11));
        if (filled) { g.setColor(PURPLE); b.setTextColor(Color.WHITE); }
        else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(a, 2), PURPLE); b.setTextColor(PURPLE); }
        b.setBackground(g);
        return b;
    }

    private static View makeLogoView(Activity a, Bitmap bmp) {
        if (bmp != null) {
            ImageView iv = new ImageView(a);
            iv.setClipToOutline(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(bmp);
            return iv;
        }
        TextView tv = new TextView(a);
        GradientDrawable g = new GradientDrawable();
        g.setColor(PURPLE);
        g.setCornerRadius(dp(a, 10));
        tv.setBackground(g);
        tv.setText("T");
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private static Bitmap loadLogo(Activity a) {
        try {
            InputStream is = a.getAssets().open("logo.png");
            Bitmap b = BitmapFactory.decodeStream(is);
            try { is.close(); } catch (Throwable ignored) {}
            return b;
        } catch (Throwable t) { return null; }
    }

    private static Bitmap downloadBitmap(String urlStr) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoInput(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0"); // some CDNs block empty UA
            int code = c.getResponseCode();
            if (code >= 400) { try { c.disconnect(); } catch (Throwable ignored) {} return null; }
            InputStream is = c.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(is);
            try { is.close(); } catch (Throwable ignored) {}
            try { c.disconnect(); } catch (Throwable ignored) {}
            return b;
        } catch (Throwable t) { return null; }
    }

    private static String readStream(InputStream is) {
        if (is == null) return null;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            try { is.close(); } catch (Throwable ignored) {}
            return sb.toString();
        } catch (Throwable t) { return null; }
    }

    private static String getPref(String k, String def) {
        try {
            if (appContext == null) return def;
            SharedPreferences sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getString(k + "_" + appContext.getPackageName(), def);
        } catch (Throwable t) { return def; }
    }

    private static void setPref(String k, String v) {
        try {
            if (appContext == null) return;
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(k + "_" + appContext.getPackageName(), v).apply();
        } catch (Throwable ignored) {}
    }

    private static void postToMain(Runnable r) {
        try { new Handler(Looper.getMainLooper()).post(r); } catch (Throwable ignored) {}
    }

    private static void postDelayedMain(Runnable r, long ms) {
        try { new Handler(Looper.getMainLooper()).postDelayed(r, ms); } catch (Throwable ignored) {}
    }

    private static boolean isDarkMode(Activity a) {
        int mask = a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static void exitApp(final Activity a) {
        try { a.finishAffinity(); } catch (Throwable ignored) {}
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { android.os.Process.killProcess(android.os.Process.myPid()); }
        }, 200);
    }
}
