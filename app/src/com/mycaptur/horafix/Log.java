package com.mycaptur.horafix;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Pequeno histórico persistente exibido na tela do app. */
final class Log {
    private static final String PREFS = "log";
    private static final String KEY = "text";
    private static final int MAX = 6000;

    private Log() {}

    static synchronized void add(Context c, String msg) {
        android.util.Log.i("HoraFix", msg);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(new Date());
        String text = p.getString(KEY, "") + stamp + "  " + msg + "\n";
        if (text.length() > MAX) text = text.substring(text.length() - MAX);
        p.edit().putString(KEY, text).commit();
    }

    static String read(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "(sem registros ainda)\n");
    }

    static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit();
    }
}
