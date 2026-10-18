package com.mycaptur.horafix;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Tenta acertar o relógio do sistema por 4 caminhos, do mais direto ao mais "emulado".
 * Cada tentativa é conferida de verdade (lendo o relógio de novo) antes de dar como sucesso.
 */
final class TimeSetter {
    private static final long TOLERANCE_MS = 30000;

    private final Context ctx;
    private final long fetchedAt;      // epoch UTC (ms) obtido da internet
    private final long fetchedElapsed; // elapsedRealtime no instante em que foi obtido

    TimeSetter(Context ctx, long fetchedAt, long fetchedElapsed) {
        this.ctx = ctx;
        this.fetchedAt = fetchedAt;
        this.fetchedElapsed = fetchedElapsed;
    }

    private long expectedNow() {
        return fetchedAt + (SystemClock.elapsedRealtime() - fetchedElapsed);
    }

    private boolean verified() {
        return Math.abs(System.currentTimeMillis() - expectedNow()) < TOLERANCE_MS;
    }

    private boolean verifiedWithin(int seconds) {
        for (int i = 0; i < seconds; i++) {
            if (verified()) return true;
            sleep(1000);
        }
        return verified();
    }

    boolean apply() {
        // A) App de sistema / assinado com a chave da plataforma: permissão SET_TIME.
        try {
            boolean r = SystemClock.setCurrentTimeMillis(expectedNow());
            if (r && verifiedWithin(2)) {
                Log.add(ctx, "OK: hora ajustada direto (SET_TIME).");
                return true;
            }
        } catch (SecurityException e) {
            Log.add(ctx, "Sem permissão SET_TIME (esperado em app comum).");
        } catch (Throwable t) {
            Log.add(ctx, "SET_TIME falhou: " + t);
        }

        // B) Root: comando date.
        if (setViaRootDate()) return true;

        // C) Alterna "Data e hora automáticas" (é o seu passo manual) via WRITE_SECURE_SETTINGS.
        if (toggleAutoTimeSetting()) return true;

        // D) Mesma alternância, via root (settings put).
        if (toggleAutoTimeViaRoot()) return true;

        return false;
    }

    // ---------------------------------------------------------------- root date

    private boolean setViaRootDate() {
        if (!hasRoot()) {
            Log.add(ctx, "Root (su) indisponível.");
            return false;
        }
        String[] variants = new String[] {
                "date -u " + fmt("MMddHHmmyyyy.ss"),
                "date @" + (expectedNow() / 1000L),
                "date -s @" + (expectedNow() / 1000L),
                "date -s " + fmt("yyyyMMdd.HHmmss"),
                "toolbox date -s " + fmt("yyyyMMdd.HHmmss"),
                "busybox date -u -s '" + fmt("yyyy-MM-dd HH:mm:ss") + "'"
        };
        for (String v : variants) {
            // recalcula o horário a cada variante (o tempo passa entre tentativas)
            if (v.startsWith("date @")) v = "date @" + (expectedNow() / 1000L);
            if (v.startsWith("date -s @")) v = "date -s @" + (expectedNow() / 1000L);
            String out = su(v + "; am broadcast -a android.intent.action.TIME_SET >/dev/null 2>&1", 8000);
            if (verifiedWithin(2)) {
                Log.add(ctx, "OK: hora ajustada via root (" + v.split(" ")[0] + ").");
                return true;
            }
            Log.add(ctx, "Variante '" + v.split(" ")[0] + "...' não funcionou. " + trim(out));
        }
        return false;
    }

    // ---------------------------------------------------------- toggle auto time

    private boolean toggleAutoTimeSetting() {
        if (ctx.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                != PackageManager.PERMISSION_GRANTED) {
            Log.add(ctx, "WRITE_SECURE_SETTINGS não concedida (veja instruções na tela do app).");
            return false;
        }
        try {
            ContentResolver cr = ctx.getContentResolver();
            setAuto(cr, 0);
            sleep(1500);
            setAuto(cr, 1);
            if (verifiedWithin(30)) {
                Log.add(ctx, "OK: alternar Data/hora automáticas fez a rede corrigir o relógio.");
                return true;
            }
            Log.add(ctx, "Alternei o automático, mas o sistema não atualizou a hora (a rede da central não fornece hora).");
        } catch (Throwable t) {
            Log.add(ctx, "Falha ao alternar automático: " + t);
        }
        return false;
    }

    private static void setAuto(ContentResolver cr, int v) {
        if (Build.VERSION.SDK_INT >= 17) {
            Settings.Global.putInt(cr, "auto_time", v);
        } else {
            Settings.System.putInt(cr, "auto_time", v);
        }
    }

    private boolean toggleAutoTimeViaRoot() {
        if (!hasRoot()) return false;
        String table = Build.VERSION.SDK_INT >= 17 ? "global" : "system";
        su("settings put " + table + " auto_time 0; sleep 1; settings put " + table + " auto_time 1", 10000);
        if (verifiedWithin(30)) {
            Log.add(ctx, "OK: alternância via root corrigiu o relógio.");
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------- utils

    private String fmt(String pattern) {
        // monta data/hora em UTC no formato pedido
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.setTimeInMillis(expectedNow() + 1000); // +1s: o comando demora a rodar
        String p = pattern;
        p = p.replace("yyyy", String.format(Locale.US, "%04d", c.get(Calendar.YEAR)));
        p = p.replace("MM", String.format(Locale.US, "%02d", c.get(Calendar.MONTH) + 1));
        p = p.replace("dd", String.format(Locale.US, "%02d", c.get(Calendar.DAY_OF_MONTH)));
        p = p.replace("HH", String.format(Locale.US, "%02d", c.get(Calendar.HOUR_OF_DAY)));
        p = p.replace("mm", String.format(Locale.US, "%02d", c.get(Calendar.MINUTE)));
        p = p.replace("ss", String.format(Locale.US, "%02d", c.get(Calendar.SECOND)));
        return p;
    }

    static boolean hasRoot() {
        return su("id", 5000).contains("uid=0");
    }

    /** Executa comandos num shell root; devolve a saída (vazia se não houver su). */
    static String su(String cmd, int timeoutMs) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            OutputStream os = p.getOutputStream();
            os.write((cmd + "\nexit\n").getBytes());
            os.flush();
            final StringBuilder sb = new StringBuilder();
            final BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    try {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    } catch (Exception ignored) { }
                }
            });
            reader.start();
            long end = SystemClock.elapsedRealtime() + timeoutMs;
            while (SystemClock.elapsedRealtime() < end) {
                try {
                    p.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    sleep(100);
                }
            }
            try { reader.join(500); } catch (InterruptedException ignored) { }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String trim(String s) {
        s = s == null ? "" : s.trim();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
