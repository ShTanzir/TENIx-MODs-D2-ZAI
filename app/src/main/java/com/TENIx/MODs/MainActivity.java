package com.TENIx.MODs;

import android.app.Activity;
import android.os.Bundle;

/**
 * TENIx MODs D2 ZAI — MainActivity
 *
 * This Activity exists ONLY to generate the two hook lines.
 * After building, extract Classes.dex with MT Manager, open MainActivity.smali
 * and you will find exactly:
 *
 *   invoke-static {p0}, Lcom/TENIx/MODs/OpenDialogbox;->show(Landroid/app/Activity;)V
 *   invoke-static {p0}, Lcom/TENIx/MODs/UpdateDialogbox;->show(Landroid/app/Activity;)V
 *
 * Copy them, delete MainActivity.smali from the dex, inject the dex into your
 * target APK, and paste these two lines inside your target Activity onCreate.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // HOOK 1 — Open Dialog Box (shows every time app opens)
        OpenDialogbox.show(this);

        // HOOK 2 — Update Dialog Box (shows ONLY when admin calls UPDATE from panel)
        UpdateDialogbox.show(this);
    }
}
