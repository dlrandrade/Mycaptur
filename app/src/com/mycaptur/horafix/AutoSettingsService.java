package com.mycaptur.horafix;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Faz o que você faria com o dedo: abre Configurações > Data e hora, troca "Data e hora
 * automáticas" para outra opção e volta para "rede". Só funciona se o usuário ativar este
 * serviço em Configurações > Acessibilidade. Não precisa de root nem de computador.
 */
public class AutoSettingsService extends AccessibilityService {
    private static volatile AutoSettingsService instance;

    // textos (sem acento, minúsculos) em português e inglês
    private static final Pattern AUTO_ROW = Pattern.compile("data e hora automatic|hora automatica|automatic date|date & time automatic|auto date");
    private static final Pattern OPT_OFF = Pattern.compile("^(desativad|desligad|off|desativar)");
    private static final Pattern OPT_GPS = Pattern.compile("gps");
    private static final Pattern OPT_NET = Pattern.compile("rede|network");
    private static final Pattern OK_BTN = Pattern.compile("^(ok|confirmar|concluir|done)$");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private int ticks;
    private int phase;          // 0 = ainda não mexi, 1 = saí de "rede", 2 = voltei para "rede"
    private long lastRowClick;

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running) return;
            if (++ticks > 45) {
                finish("tempo esgotado (não achei a tela esperada).");
                return;
            }
            try {
                step();
            } catch (Throwable t) {
                Log.add(getApplicationContext(), "Acessibilidade: erro " + t);
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

    /** Pode ser chamada de qualquer thread. @return false se o serviço não está ativado. */
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

    // ------------------------------------------------------------------ máquina de passos

    private void begin() {
        if (running) return;
        running = true;
        ticks = 0;
        phase = 0;
        lastRowClick = 0;
        Log.add(this, "Acessibilidade: abrindo Data e hora nas Configurações...");
        try {
            Intent i = new Intent(Settings.ACTION_DATE_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            finish("não consegui abrir Data e hora: " + t);
            return;
        }
        handler.postDelayed(tick, 1800);
    }

    private void step() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        List<AccessibilityNodeInfo> choices = new ArrayList<AccessibilityNodeInfo>();
        collectChoices(root, choices, 0);

        if (!choices.isEmpty()) {
            // diálogo com as opções (rede / GPS / desativado)
            if (phase == 0) {
                AccessibilityNodeInfo opt = pick(choices, OPT_OFF);
                if (opt == null) opt = pick(choices, OPT_GPS);
                if (opt == null) {
                    finish("o diálogo não tem opção 'Desativado' nem 'GPS'.");
                    return;
                }
                click(opt);
                clickOk(root);
                phase = 1;
                Log.add(this, "Acessibilidade: saí de 'rede'.");
            } else if (phase == 1) {
                AccessibilityNodeInfo opt = pick(choices, OPT_NET);
                if (opt == null) {
                    finish("não achei a opção de rede para voltar.");
                    return;
                }
                click(opt);
                clickOk(root);
                phase = 2;
                Log.add(this, "Acessibilidade: voltei para 'rede'.");
                handler.postDelayed(new Runnable() {
                    public void run() {
                        finish("concluído.");
                    }
                }, 1200);
                running = true; // aguarda o finish agendado
                ticks = 1000;   // impede novos passos
            }
            return;
        }

        // sem diálogo: procurar a linha "Data e hora automáticas"
        AccessibilityNodeInfo row = findText(root, AUTO_ROW, 0);
        if (row == null) return; // tela ainda carregando
        AccessibilityNodeInfo clickable = clickableAncestor(row);
        long now = System.currentTimeMillis();
        if (now - lastRowClick < 1500) return;

        if (hasSwitch(clickable, 0)) {
            // linha com chave liga/desliga: clica duas vezes (desliga e liga)
            if (phase == 0) {
                click(clickable);
                phase = 1;
                lastRowClick = now;
                Log.add(this, "Acessibilidade: chave desligada.");
            } else if (phase == 1) {
                click(clickable);
                phase = 2;
                Log.add(this, "Acessibilidade: chave ligada.");
                ticks = 1000;
                handler.postDelayed(new Runnable() {
                    public void run() {
                        finish("concluído.");
                    }
                }, 1200);
            }
        } else {
            click(clickable); // abre o diálogo
            lastRowClick = now;
        }
    }

    private void finish(String msg) {
        if (!running) return;
        running = false;
        handler.removeCallbacks(tick);
        Log.add(this, "Acessibilidade: " + msg);
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(new Runnable() {
            public void run() {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        }, 500);
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

    private static void collectChoices(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 25) return;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (cls.endsWith("RadioButton") || cls.endsWith("CheckedTextView")) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectChoices(n.getChild(i), out, depth + 1);
    }

    private static AccessibilityNodeInfo pick(List<AccessibilityNodeInfo> list, Pattern p) {
        for (AccessibilityNodeInfo n : list) {
            if (p.matcher(label(n)).find()) return n;
        }
        return null;
    }

    private static AccessibilityNodeInfo findText(AccessibilityNodeInfo n, Pattern p, int depth) {
        if (n == null || depth > 25) return null;
        if (p.matcher(label(n)).find()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findText(n.getChild(i), p, depth + 1);
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

    private static boolean hasSwitch(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 6) return false;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        if (cls.endsWith("Switch") || cls.endsWith("CheckBox") || cls.endsWith("ToggleButton")) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            if (hasSwitch(n.getChild(i), depth + 1)) return true;
        }
        return false;
    }

    private static void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickableAncestor(n);
        if (c != null) c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static void clickOk(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo ok = findText(root, OK_BTN, 0);
        if (ok != null) click(ok);
    }
}
