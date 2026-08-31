package com.opentasker.relay;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.widget.Toast;
import java.util.ArrayList;

/**
 * Standalone relay stub: one per share target, installed as its own package so it gets its own tile
 * in the system share sheet. It forwards the share to the main app's ShareForwardActivity, which does
 * the real unfreeze + forward + freeze-bubble. The target package is read from this relay's own
 * manifest meta-data, so this .dex is byte-identical for every relay.
 */
public class RelayActivity extends Activity {
    private static final String MAIN_PKG = "shiroikuma.jiyusagyoban";
    private static final String HANDLER = "com.opentasker.core.share.ShareForwardActivity";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        String target = readTarget();
        String action = in != null ? in.getAction() : null;
        if (target == null || (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action))) {
            finish();
            return;
        }
        Intent out = new Intent(action);
        out.setComponent(new ComponentName(MAIN_PKG, HANDLER));
        out.putExtra(Intent.EXTRA_SHORTCUT_ID, "share_" + target);
        out.setType(in.getType());
        copyCs(in, out, Intent.EXTRA_TEXT);
        copyCs(in, out, Intent.EXTRA_SUBJECT);
        copyCs(in, out, Intent.EXTRA_HTML_TEXT);

        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (Intent.ACTION_SEND.equals(action)) {
            Parcelable p = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) uris.add((Uri) p);
        } else {
            ArrayList<Uri> us = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (us != null) for (Uri u : us) if (u != null) uris.add(u);
        }
        ClipData cd = in.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null && !uris.contains(u)) uris.add(u);
            }
        }
        if (!uris.isEmpty()) {
            if (Intent.ACTION_SEND_MULTIPLE.equals(action)) out.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            else out.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            ClipData ncd = ClipData.newUri(getContentResolver(), "shared", uris.get(0));
            for (int i = 1; i < uris.size(); i++) ncd.addItem(new ClipData.Item(uris.get(i)));
            out.setClipData(ncd);
            out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(out);
        } catch (Exception e) {
            Toast.makeText(this, "自由作業盤 not installed", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private String readTarget() {
        try {
            ActivityInfo ai = getPackageManager().getActivityInfo(getComponentName(), PackageManager.GET_META_DATA);
            if (ai.metaData != null) return ai.metaData.getString("share.target");
        } catch (Exception e) {
        }
        return null;
    }

    private static void copyCs(Intent in, Intent out, String key) {
        CharSequence v = in.getCharSequenceExtra(key);
        if (v != null) out.putExtra(key, v);
    }
}
