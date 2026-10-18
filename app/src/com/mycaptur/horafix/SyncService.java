package com.mycaptur.horafix;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

public class SyncService extends Service {
    /** Antes de 2025 consideramos que o relógio está com a data de fábrica (ex.: 2007). */
    private static final long YEAR_2025 = 1735689600000L;
    private static final long MAX_WAIT_MS = 15L * 60 * 1000; // espera o hotspot/internet por até 15 min
    private static final int MAX_SET_FAILURES = 3;

    private static volatile boolean running;

    static boolean clockLooksWrong() {
        return System.currentTimeMillis() < YEAR_2025;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            final Context app = getApplicationContext();
            new Thread(new Runnable() {
                public void run() {
                    PowerManager.WakeLock wl = ((PowerManager) app.getSystemService(Context.POWER_SERVICE))
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "horafix");
                    wl.acquire(MAX_WAIT_MS + 120000);
                    try {
                        work(app);
                    } catch (Throwable t) {
                        Log.add(app, "Erro inesperado: " + t);
                    } finally {
                        if (wl.isHeld()) wl.release();
                        running = false;
                        stopSelf();
                    }
                }
            }).start();
        }
        return START_NOT_STICKY;
    }

    private void work(Context app) throws InterruptedException {
        Log.add(app, "Iniciando. Relógio atual do aparelho está " + (clockLooksWrong() ? "ERRADO (antes de 2025)." : "com ano plausível."));
        long startedAt = SystemClock.elapsedRealtime();
        int setFailures = 0;
        Thread.sleep(3000);
        while (SystemClock.elapsedRealtime() - startedAt < MAX_WAIT_MS) {
            long t = TimeSource.fetch(app);
            long fetchedElapsed = SystemClock.elapsedRealtime();
            if (t < 0) {
                Thread.sleep(10000); // ainda sem internet: tenta de novo
                continue;
            }
            long diff = Math.abs(t - System.currentTimeMillis());
            if (diff < 60000) {
                Log.add(app, "Relógio já está correto (diferença " + (diff / 1000) + "s). Nada a fazer.");
                return;
            }
            Log.add(app, "Relógio está errado por " + (diff / 86400000L) + " dia(s). Corrigindo...");
            if (new TimeSetter(app, t, fetchedElapsed).apply()) {
                return;
            }
            if (++setFailures >= MAX_SET_FAILURES) {
                Log.add(app, "NÃO CONSEGUI ajustar a hora. É preciso root, ou conceder WRITE_SECURE_SETTINGS via adb (veja a tela do app).");
                return;
            }
            Thread.sleep(15000);
        }
        Log.add(app, "Sem internet por 15 minutos. Desisti desta vez.");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
