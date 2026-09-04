package com.TENIx.MODs;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

/**
 * TENIx MODs — OPEN DIALOG BOX
 * Square card with radius • White/Black auto dark-light theme • Purple border.
 * Spinning purple ring animation around logo (logo loaded from assets/logo.png).
 * Not dismissible — it ALWAYS shows until the user presses EXIT
 * (JOIN TELEGRAM opens https://t.me/TENIxMODs).
 *
 * Hook: invoke-static {p0}, Lcom/TENIx/MODs/OpenDialogbox;->show(Landroid/app/Activity;)V
 */
public class OpenDialogbox {

    /* ============ CONFIG — searchable/editable in dex strings after build ==== */
    public static String TELEGRAM_LINK = "https://t.me/TENIxMODs";
    public static String OPEN_NAME     = "TENIx MODs";
    public static String OPEN_DESC     = "Crafted mods & premium unlocks — smooth, clean, pure passion.";
    /* ========================================================================= */

    private static final int PURPLE = 0xFF7C3AED;

    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        final boolean dark = isDarkMode(activity);
        final int bg   = dark ? 0xFF0F0F14 : 0xFFFFFFFF;
        final int text = dark ? 0xFFF3F3F5 : 0xFF141418;
        final int sub  = dark ? 0xFF9A9AA5 : 0xFF5A5A66;

        /* ---------------- rounded card with purple border ---------------- */
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(bg);
        cardBg.setCornerRadius(dp(activity, 26));
        cardBg.setStroke(dp(activity, 2), PURPLE);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(cardBg);
        int p = dp(activity, 24);
        card.setPadding(p, p + dp(activity, 8), p, p);

        /* ---------------- logo + spinning purple ring ---------------- */
        FrameLayout logoArea = new FrameLayout(activity);
        logoArea.addView(new SpinRing(activity),
                new FrameLayout.LayoutParams(dp(activity, 116), dp(activity, 116)));

        FrameLayout logoSlot = new FrameLayout(activity);
        GradientDrawable slotBg = new GradientDrawable();
        slotBg.setColor((PURPLE & 0x00FFFFFF) | 0x1A000000);
        slotBg.setCornerRadius(dp(activity, 24));
        slotBg.setStroke(dp(activity, 1), (PURPLE & 0x00FFFFFF) | 0x40000000);
        logoSlot.setBackground(slotBg);
        logoSlot.addView(makeLogoView(activity, loadLogo(activity)),
                new FrameLayout.LayoutParams(dp(activity, 56), dp(activity, 56), Gravity.CENTER));
        logoArea.addView(logoSlot,
                new FrameLayout.LayoutParams(dp(activity, 82), dp(activity, 82), Gravity.CENTER));

        card.addView(logoArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------------- name + description ---------------- */
        TextView name = new TextView(activity);
        name.setText(OPEN_NAME);
        name.setTextColor(text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        card.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView desc = new TextView(activity);
        desc.setText(OPEN_DESC);
        desc.setTextColor(sub);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(dp(activity, 2), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(activity, 8);
        card.addView(desc, dlp);

        /* ---------------- buttons ---------------- */
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView join = pillButton(activity, "JOIN TELEGRAM", true);
        TextView exit = pillButton(activity, "EXIT", false);

        row.addView(join, new LinearLayout.LayoutParams(0, dp(activity, 46), 1f));
        row.addView(new View(activity), new LinearLayout.LayoutParams(dp(activity, 12), 1));
        row.addView(exit, new LinearLayout.LayoutParams(0, dp(activity, 46), 1f));

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(activity, 22);
        card.addView(row, rlp);

        join.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_LINK)));
                } catch (Throwable t) {
                    Toast.makeText(activity, "No browser found", Toast.LENGTH_SHORT).show();
                }
            }
        });
        exit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                exitApp(activity);
            }
        });

        /* ---------------- outer padding + dialog ---------------- */
        FrameLayout outer = new FrameLayout(activity);
        outer.setPadding(dp(activity, 22), dp(activity, 22), dp(activity, 22), dp(activity, 22));
        outer.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);              // ← ALWAYS shows until EXIT
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(outer);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
        dialog.show();
    }

    /* ============================ helpers ============================ */

    private static TextView pillButton(Activity a, String label, boolean filled) {
        TextView b = new TextView(a);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(a, 14));
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
            GradientDrawable g = new GradientDrawable();
            g.setColor((PURPLE & 0x00FFFFFF) | 0x14000000);
            g.setCornerRadius(dp(a, 12));
            iv.setBackground(g);
            iv.setClipToOutline(true);            // rounded-square logo (API 21+)
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(bmp);
            return iv;
        }
        TextView tv = new TextView(a);            // fallback logo if assets/logo.png missing
        GradientDrawable g = new GradientDrawable();
        g.setColor(PURPLE);
        g.setCornerRadius(dp(a, 12));
        tv.setBackground(g);
        tv.setText("T");
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private static Bitmap loadLogo(Activity a) {
        try {
            InputStream is = a.getAssets().open("logo.png");   // ← assets/logo.png
            Bitmap b = BitmapFactory.decodeStream(is);
            try { is.close(); } catch (Throwable ignored) {}
            return b;
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
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, 200);
    }

    /* ================= spinning purple ring around the logo ================= */
    private static class SpinRing extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect  = new RectF();

        SpinRing(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(dp(c, 3));
            track.setColor((PURPLE & 0x00FFFFFF) | 0x26000000);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(dp(c, 4));
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(PURPLE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float inset = arc.getStrokeWidth();
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawArc(rect, 0f, 360f, false, track);
            float angle = (System.currentTimeMillis() % 1600L) / 1600f * 360f;
            canvas.drawArc(rect, angle, 285f, false, arc);
            postInvalidateOnAnimation();          // continuous smooth rotation
        }
    }
}
