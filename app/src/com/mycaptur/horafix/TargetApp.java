package com.mycaptur.horafix;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** App que o Hora Fix abre depois de corrigir a hora (por padrão, o "VZZA TV Play"). */
final class TargetApp {
    private static final String PREFS = "target";
    private static final String KEY_PKG = "pkg";
    private static final String DEFAULT_NAME = "vzza";
    private static final long MIN_GAP_MS = 5L * 60 * 1000; // evita reabrir a cada gatilho seguido

    private static long lastLaunch; // elapsedRealtime

    private TargetApp() {}

    static final class Entry {
        final String label;
        final String pkg;
        Entry(String label, String pkg) { this.label = label; this.pkg = pkg; }
    }

    static List<Entry> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<Entry> out = new ArrayList<Entry>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo.packageName.equals(c.getPackageName())) continue;
            out.add(new Entry(String.valueOf(ri.loadLabel(pm)), ri.activityInfo.packageName));
        }
        Collections.sort(out, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) { return a.label.compareToIgnoreCase(b.label); }
        });
        return out;
    }

    static void choose(Context c, String pkg) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PKG, pkg).commit();
    }

    /** Pacote escolhido pelo usuário ou, na falta dele, o primeiro app cujo nome contém "vzza". */
    static Entry current(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = p.getString(KEY_PKG, null);
        for (Entry e : installed(c)) {
            if (saved != null ? e.pkg.equals(saved) : norm(e.label).contains(DEFAULT_NAME)) return e;
        }
        return null;
    }

    static void launch(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (lastLaunch != 0 && now - lastLaunch < MIN_GAP_MS) {
            Log.add(c, "App final já foi aberto há pouco; não abri de novo.");
            return;
        }
        Entry e = current(c);
        if (e == null) {
            Log.add(c, "App final NÃO encontrado (instale o VZZA TV Play ou escolha outro no botão 4).");
            return;
        }
        Intent i = c.getPackageManager().getLaunchIntentForPackage(e.pkg);
        if (i == null) {
            Log.add(c, "Não consegui abrir " + e.label + ".");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        lastLaunch = now;
        Log.add(c, "Abri " + e.label + " (" + e.pkg + ").");
    }

    private static String norm(String s) {
        String n = Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD);
        return n.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
