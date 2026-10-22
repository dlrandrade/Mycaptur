package com.mycaptur.horafix;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Repete, com toques automáticos, o passo a passo manual que corrige a hora da central:
 *   1) abrir Data e hora (tocando no relógio / tela de Configurações)
 *   2) "Data e hora automáticas" -> "Usar tempo fornecido pelo GPS" -> OK
 *   3) "Data e hora automáticas" de novo -> "Usar tempo fornecido pela rede"
 *   4) voltar para a tela inicial
 * Só funciona com este serviço ativado em Configurações > Acessibilidade (sem root, sem PC).
 */
public class AutoSettingsService extends AccessibilityService {
    private static volatile AutoSettingsService instance;

    // textos já sem acento e em minúsculas; português e inglês
    private static final Pattern AUTO_ROW = Pattern.compile("data e hora automatic|hora automatica|automatic date|date & time automatic|auto date");
    private static final Pattern OPT_GPS = Pattern.compile("gps");
    private static final Pattern OPT_NET = Pattern.compile("rede|network");
    private static final Pattern NOT_ZONE = Pattern.compile("fuso|zone|zona");
    private static final Pattern OK_BTN = Pattern.compile("^(ok|confirmar|concluir|done)$");
    private static final Pattern CLOCK = Pattern.compile("^\\d{1,2}:\\d{2}( ?(am|pm))?$");

    private static final int MAX_TICKS = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private boolean dumpMode;
    private int ticks;
    private int phase;          // 0 abrir diálogo | 1 GPS escolhido | 2 reabrir diálogo | 3 rede escolhida
    private int rowMissing;
    private long lastRowClick;  // elapsedRealtime (o relógio do aparelho pode mudar no meio do processo)

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running) return;
            if (++ticks > MAX_TICKS && ticks < 900) {
                dump(getRootInActiveWindow());
                finish("tempo esgotado (não achei a tela esperada).");
                return;
            }
            try {
                step();
            } catch (Throwable t) {
                Log.add(AutoSettingsService.this, "Acessibilidade: erro " + t);
            }
            if (running) handler.postDelayed(this, 700);
        }
    };

    static boolean isActive() {
        return instance != null;
    }

    static boolean isRunning() {
        return instance != null && instance.running;
    }

    /** Roda o passo a passo. Pode ser chamada de qualquer thread. @return false se o serviço não está ativado. */
    static boolean request() {
        final AutoSettingsService s = instance;
        if (s == null) return false;
        s.handler.post(new Runnable() {
            public void run() {
                s.begin();
            }
        });
        return true;
    }

    /** Só grava no log o que o serviço enxerga na tela de Data e hora (e no diálogo). */
    static boolean requestDump() {
        final AutoSettingsService s = instance;
        if (s == null) return false;
        s.handler.post(new Runnable() {
            public void run() {
                s.dumpMode = true;
                s.begin();
            }
        });
        return true;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.add(this, "Serviço de acessibilidade ativo.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        running = false;
        return super.onUnbind(intent);
    }

    // ------------------------------------------------------------------ passos

    private void begin() {
        if (running) return;
        running = true;
        ticks = 0;
        phase = 0;
        rowMissing = 0;
        lastRowClick = 0;
        Log.add(this, dumpMode ? "Diagnóstico: abrindo Data e hora..." : "Passo 1: abrindo Data e hora...");
        try {
            Intent i = new Intent(Settings.ACTION_DATE_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.add(this, "Não abriu por Configurações (" + t + "); vou tocar no relógio da barra.");
            openViaStatusBarClock();
        }
        handler.postDelayed(tick, 1800);
    }

    private void step() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        if (dumpMode) {
            if (ticks == 3) {
                dump(root);
                AccessibilityNodeInfo r = findText(root, AUTO_ROW, null, 0);
                if (r != null) click(r);
                else Log.add(this, "Diagnóstico: linha 'Data e hora automáticas' NÃO encontrada.");
            } else if (ticks >= 6) {
                dump(root);
                dumpMode = false;
                finish("diagnóstico concluído.");
            }
            return;
        }

        // o diálogo de escolha tem, ao mesmo tempo, a opção GPS e a opção rede
        AccessibilityNodeInfo gps = findText(root, OPT_GPS, NOT_ZONE, 0);
        AccessibilityNodeInfo net = findText(root, OPT_NET, NOT_ZONE, 0);
        boolean dialog = gps != null && net != null;
        long now = SystemClock.elapsedRealtime();

        switch (phase) {
            case 0: // abrir "Data e hora automáticas" e escolher GPS
                if (dialog) {
                    click(gps);
                    phase = 1;
                    Log.add(this, "Passo 2: escolhi 'tempo fornecido pelo GPS'.");
                    return;
                }
                openRow(root, now);
                return;

            case 1: // confirmar com OK
                AccessibilityNodeInfo ok = findText(root, OK_BTN, null, 0);
                if (ok != null) {
                    click(ok);
                    phase = 2;
                    lastRowClick = now;
                    Log.add(this, "Passo 3: confirmei com OK.");
                } else if (!dialog) {
                    phase = 2; // o diálogo já fechou sozinho
                }
                return;

            case 2: // reabrir e escolher rede
                if (dialog) {
                    click(net);
                    phase = 3;
                    Log.add(this, "Passo 4: escolhi 'tempo fornecido pela rede'.");
                    return;
                }
                openRow(root, now);
                return;

            case 3: // OK, se existir; depois voltar para a tela inicial
                AccessibilityNodeInfo ok2 = findText(root, OK_BTN, null, 0);
                if (ok2 != null) click(ok2);
                if (ok2 != null || !dialog) {
                    ticks = 1000; // impede novos passos
                    handler.postDelayed(new Runnable() {
                        public void run() {
                            finish("concluído; voltando para a tela inicial.");
                        }
                    }, 1500);
                }
                return;
            default:
        }
    }

    private void openRow(AccessibilityNodeInfo root, long now) {
        AccessibilityNodeInfo row = findText(root, AUTO_ROW, null, 0);
        if (row == null) {
            // tela de Data e hora ainda não apareceu: tenta o toque no relógio da barra de status
            if (++rowMissing == 5) openViaStatusBarClock();
            return;
        }
        if (now - lastRowClick < 1500) return;
        click(row);
        lastRowClick = now;
    }

    /** Equivale a "tocar na hora" da central: procura o relógio (ex.: 04:29) em qualquer janela. */
    private void openViaStatusBarClock() {
        if (Build.VERSION.SDK_INT < 21) {
            Log.add(this, "Android antigo demais para localizar o relógio da barra.");
            return;
        }
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                AccessibilityNodeInfo c = findText(r, CLOCK, null, 0);
                if (c != null) {
                    click(c);
                    Log.add(this, "Toquei no relógio da barra.");
                    return;
                }
            }
            Log.add(this, "Não encontrei o relógio na barra de status.");
        } catch (Throwable t) {
            Log.add(this, "Falha ao tocar no relógio: " + t);
        }
    }

    private void finish(String msg) {
        if (!running) return;
        running = false;
        handler.removeCallbacks(tick);
        Log.add(this, "Acessibilidade: " + msg);
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    // ------------------------------------------------------------------ utilidades de nós

    private static String norm(CharSequence cs) {
        if (cs == null) return "";
        String s = Normalizer.normalize(cs.toString().toLowerCase(), Normalizer.Form.NFD);
        return s.replaceAll("\\p{InCombiningDiacriticalMarks}+", "").trim();
    }

    private static String label(AccessibilityNodeInfo n) {
        String t = norm(n.getText());
        return t.length() > 0 ? t : norm(n.getContentDescription());
    }

    private static AccessibilityNodeInfo findText(AccessibilityNodeInfo n, Pattern include, Pattern exclude, int depth) {
        if (n == null || depth > 25) return null;
        String l = label(n);
        if (l.length() > 0 && include.matcher(l).find() && (exclude == null || !exclude.matcher(l).find())) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findText(n.getChild(i), include, exclude, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return n;
    }

    private static void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickableAncestor(n);
        if (c != null) c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private void dump(AccessibilityNodeInfo root) {
        if (root == null) {
            Log.add(this, "Tela: (sem conteúdo acessível)");
            return;
        }
        StringBuilder sb = new StringBuilder();
        dumpInto(root, sb, 0);
        Log.add(this, "Tela: " + (sb.length() > 700 ? sb.substring(0, 700) : sb.toString()));
    }

    private static void dumpInto(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 25 || sb.length() > 700) return;
        String t = label(n);
        if (t.length() > 0) {
            String cls = n.getClassName() == null ? "?" : n.getClassName().toString();
            sb.append(cls.substring(cls.lastIndexOf('.') + 1)).append(':').append(t).append(" | ");
        }
        for (int i = 0; i < n.getChildCount(); i++) dumpInto(n.getChild(i), sb, depth + 1);
    }
}
