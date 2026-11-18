package com.ino.speedtracer;

import android.content.Context;

/** アプリの版(versionName)を取る */
final class BuildConfigCompat {
    private BuildConfigCompat() { }

    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
