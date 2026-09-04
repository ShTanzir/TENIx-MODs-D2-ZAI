package com.TENIx.MODs;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * TENIx MODs — UPDATE DIALOG BOX
 * 320×360 rounded card • auto dark/light • purple border + purple ✦ bullets.
 * Reads:  apps/{packageId_with_underscores}/update  from Firebase Realtime DB (REST).
 * If "active" is true → shows Update Dialog + Notification (with image).
 * Everything is try/catch protected — the host app can NEVER crash.
 *
 * Hook: invoke-static {p0}, Lcom/TENIx/MODs/UpdateDialogbox;->show(Landroid/app/Activity;)V
 */
public class UpdateDialogbox {

    /* ==== CONFIG — search & replace these strings in the dex after build =====
       REPLACE_FIREBASE_REALTIME_DB_URL -> https://your-project-default-rtdb.firebaseio.com/
       REPLACE_FIREBASE_DB_SECRET       -> legacy database secret (or leave placeholder) */
    public static String FIREBASE_DB_URL    = "REPLACE_FIREBASE_REALTIME_DB_URL";
    public static String FIREBASE_DB_SECRET = "REPLACE_FIREBASE_DB_SECRET";
    /* ========================================================================= */

    private static final int PURPLE   = 0xFF7C3AED;
    private static final int NOTIF_ID = 7331;

    private static boolean dialogShowing = false;
    private static boolean notified      = false;

    /** HOOK ENTRY POINT */
    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        new Thread(new Runnable() {
            @Override public void run() { checkForUpdate(activity); }
        }).start();
    }

    /* =======================================================================
       FIREBASE (REST) — reads apps/{pkg}.json in background thread
       ======================================================================= */
    private static void checkForUpdate(final Activity act) {
        try {
            String base = (FIREBASE_DB_URL == null) ? "" : FIREBASE_DB_URL.trim();
            if (base.isEmpty() || base.contains("REPLACE_")) return;   // not configured yet
            if (!base.endsWith("/")) base = base + "/";
            String key = act.getPackageName().replace('.', '_');       // com.test.app -> com_test_app
            String urlStr = base + "apps/" + key + ".json";

            String secret = (FIREBASE_DB_SECRET == null) ? "" : FIREBASE_DB_SECRET.trim();
            if (!secret.isEmpty() && !secret.contains("REPLACE_")) {
                urlStr = urlStr + "?auth=" + URLEncoder.encode(secret, "UTF-8");
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            InputStream in = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String body = readStream(in);
            try { conn.disconnect(); } catch (Throwable ignored) {}

            if (body == null || body.length() < 2 || "null".equals(body)) return;

            JSONObject rootNode = new JSONObject(body);
            JSONObject up = rootNode.optJSONObject("update");
            if (up == null) up = rootNode;                             // flat structure also allowed

            boolean active = up.optBoolean("active", false) || up.optBoolean("updateActive", false);
            if (!active) return;

            final String version   = up.optString("version", "");
            final String date      = up.optString("releaseDate", up.optString("date", ""));
            final String desc      = up.optString("description", "");
            final String features  = up.optString("features", "");
            final String updateUrl = up.optString("updateUrl", up.optString("url", ""));
            final String imageUrl  = up.optString("imageUrl", "");

            final Bitmap bigPic = (imageUrl.trim().isEmpty()) ? null : downloadBitmap(imageUrl.trim());

            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try {
                        if (act.isFinishing() || act.isDestroyed()) return;
                        notifyUpdate(act, version, desc, bigPic);
                        showUpdateDialog(act, version, date, desc, features, updateUrl);
                    } catch (Throwable t) {
                        dialogShowing = false;
                    }
                }
            });
        } catch (Throwable t) {
            /* no internet / wrong url / bad json — NEVER crash the host app */
        }
    }

    /* =======================================================================
       NOTIFICATION (with image via BigPictureStyle)
       ======================================================================= */
    private static void notifyUpdate(Context ctx, String version, String desc, Bitmap img) {
        if (notified) return;
        notified = true;
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            /* Android 13+ runtime notification permission */
            if (Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                       != PackageManager.PERMISSION_GRANTED
                    && ctx instanceof Activity) {
                try {
                    ((Activity) ctx).requestPermissions(
                            new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIF_ID);
                } catch (Throwable ignored) {}
            }

            String text  = (desc == null || desc.trim().isEmpty())
                    ? "A new version is ready — update now!" : desc.trim();
            String title = "New Update"
                    + ((version == null || version.trim().isEmpty()) ? "" : " • v" + version.trim());

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        "TENIx_Update_Channel", "App Updates", NotificationManager.IMPORTANCE_HIGH);
                ch.enableLights(true);
                ch.setLightColor(PURPLE);
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
                b = new Notification.Builder(ctx, "TENIx_Update_Channel");
            } else {
                b = new Notification.Builder(ctx);
            }

            b.setSmallIcon(android.R.drawable.stat_notify_more)
             .setContentTitle(title)
             .setContentText(text)
             .setAutoCancel(true);

            if (img != null) {
                b.setStyle(new Notification.BigPictureStyle()
                        .bigPicture(img)
                        .setBigContentTitle(title)
                        .setSummaryText(text));
            } else {
                b.setStyle(new Notification.BigTextStyle().bigText(text));
            }

            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable t) {
            /* permission denied — dialog still shows, never crash */
        }
    }

    /* =======================================================================
       DIALOG UI — 320×360, radius 28, exact element positions from the spec
       ======================================================================= */
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
        cardBg.setCornerRadius(dp(act, 28));                 // corner radius 28
        cardBg.setStroke(dp(act, 2), PURPLE);                // purple border

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(cardBg);
        root.setMinimumHeight(dp(act, 360));                 // dialog size: 320 × 360

        /* ---------- 1. HEADER : "New Update" (top-left) + logo (top-right) ---------- */
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
        slotBg.setCornerRadius(dp(act, 14));                 // rounded-square icon
        slotBg.setStroke(dp(act, 1), (PURPLE & 0x00FFFFFF) | 0x40000000);
        logoSlot.setBackground(slotBg);
        logoSlot.addView(makeLogoView(act, loadLogo(act)),
                new FrameLayout.LayoutParams(dp(act, 40), dp(act, 40), Gravity.CENTER));
        header.addView(logoSlot, new LinearLayout.LayoutParams(dp(act, 56), dp(act, 56)));

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------- 2. VERSION ---------- */
        TextView v = new TextView(act);
        v.setText((version == null || version.trim().isEmpty())
                ? "Version —" : "Version " + version.trim());
        v.setTextColor(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(v, sectionLabel(v, 14));

        /* ---------- 3. RELEASE DATE ---------- */
        TextView d = new TextView(act);
        d.setText((date == null || date.trim().isEmpty())
                ? "Release —" : "Release " + date.trim());
        d.setTextColor(sub);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(d, sectionLabel(d, 8));

        /* ---------- 4. DIVIDER ---------- */
        View divider = new View(act);
        divider.setBackgroundColor(line);
        LinearLayout.LayoutParams dvp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1));
        dvp.leftMargin  = dp(act, 16);
        dvp.rightMargin = dp(act, 16);
        dvp.topMargin   = dp(act, 14);
        root.addView(divider, dvp);

        /* ---------- 5. WHAT'S NEW ---------- */
        TextView wn = new TextView(act);
        wn.setText("What's New");
        wn.setTextColor(text);
        wn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        wn.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(wn, sectionLabel(wn, 14));

        /* ---------- 6. FEATURE LIST (scrollable, purple ✦ bullets) ---------- */
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        int count = 0;
        if (features != null && !features.trim().isEmpty()) {
            String[] lines = features.split("\n");
            for (String rawLine : lines) {
                String l = rawLine.trim();
                if (l.startsWith("✦")) l = l.substring(1).trim();
                if (l.isEmpty()) continue;
                list.addView(featureRow(act, l, text));
                count++;
            }
        }
        if (count == 0) {
            String fb = (desc != null && !desc.trim().isEmpty())
                    ? desc.trim() : "Bug fixes and improvements";
            list.addView(featureRow(act, fb, text));
        }
        ScrollView scroller = new ScrollView(act);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.leftMargin  = dp(act, 16);
        slp.rightMargin = dp(act, 16);
        slp.topMargin   = dp(act, 6);
        root.addView(scroller, slp);

        /* ---------- 7. BUTTONS : EXIT (bottom-left) + UPDATE (bottom-right) ---------- */
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView exitB = pillButton(act, "EXIT", false);
        TextView updB  = pillButton(act, "UPDATE", true);

        row.addView(exitB, new LinearLayout.LayoutParams(0, dp(act, 44), 1f));
        row.addView(new View(act), new LinearLayout.LayoutParams(dp(act, 12), 1));
        row.addView(updB,  new LinearLayout.LayoutParams(0, dp(act, 44), 1f));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin   = dp(act, 16);
        blp.rightMargin  = dp(act, 16);
        blp.topMargin    = dp(act, 14);
        blp.bottomMargin = dp(act, 20);
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

        /* ---------- show dialog ---------- */
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
            w.setLayout(dp(act, 320), dp(act, 360));       // exact 320 × 360, centered
            w.setGravity(Gravity.CENTER);
        }
        dialog.show();
    }

    /* ============================ helpers ============================ */

    private static LinearLayout.LayoutParams sectionLabel(TextView tv, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin  = dp(tv.getContext(), 16);
        lp.rightMargin = dp(tv.getContext(), 16);
        lp.topMargin   = dp(tv.getContext(), topMargin);
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
        g.setCornerRadius(dp(a, 11));                        // button radius ~11
        if (filled) {
            g.setColor(PURPLE);
            b.setTextColor(Color.WHITE);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(a, 2), PURPLE);
            b.setTextColor(PURPLE);
        }
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
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap downloadBitmap(String urlStr) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoInput(true);
            int code = c.getResponseCode();
            if (code >= 400) { try { c.disconnect(); } catch (Throwable ignored) {} return null; }
            InputStream is = c.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(is);
            try { is.close(); } catch (Throwable ignored) {}
            try { c.disconnect(); } catch (Throwable ignored) {}
            return b;
        } catch (Throwable t) {
            return null;
        }
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
        } catch (Throwable t) {
            return null;
        }
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
            @Override public void run() {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, 200);
    }
}
