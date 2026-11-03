package com.dicema.comandaprint;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Registro simple guardado en SharedPreferences, para ver qué pasa dentro del servicio. */
final class Logger {

    private static final String KEY = "log";

    private Logger() {
    }

    static synchronized void add(Context c, String msg) {
        try {
            SharedPreferences sp = c.getSharedPreferences(EscposPrintService.PREFS, Context.MODE_PRIVATE);
            String old = sp.getString(KEY, "");
            String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + msg + "\n";
            String all = line + old;
            if (all.length() > 4000) all = all.substring(0, 4000);
            sp.edit().putString(KEY, all).commit();
        } catch (Throwable ignored) {
        }
    }

    static String get(Context c) {
        return c.getSharedPreferences(EscposPrintService.PREFS, Context.MODE_PRIVATE).getString(KEY, "(vacío)");
    }

    static void clear(Context c) {
        c.getSharedPreferences(EscposPrintService.PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit();
    }
}
