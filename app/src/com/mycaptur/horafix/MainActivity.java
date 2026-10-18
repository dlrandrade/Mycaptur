package com.mycaptur.horafix;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Date;

public class MainActivity extends Activity {
    private TextView status;
    private TextView logView;
    private final Handler handler = new Handler();
    private volatile String rootState = "não testado";

    private final Runnable refresh = new Runnable() {
        public void run() {
            render();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        status = new TextView(this);
        status.setTextSize(16);
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);

        Button fix = new Button(this);
        fix.setText("Corrigir hora agora");
        fix.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startService(new Intent(MainActivity.this, SyncService.class));
            }
        });
        row.addView(fix);

        Button test = new Button(this);
        test.setText("Testar root");
        test.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                rootState = "testando...";
                new Thread(new Runnable() {
                    public void run() {
                        rootState = TimeSetter.hasRoot() ? "SIM" : "NÃO";
                    }
                }).start();
            }
        });
        row.addView(test);

        Button clear = new Button(this);
        clear.setText("Limpar log");
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.clear(MainActivity.this);
                render();
            }
        });
        row.addView(clear);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row2);

        Button acc = new Button(this);
        acc.setText("1) Ativar acessibilidade");
        acc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    Log.add(MainActivity.this, "Esta central não tem tela de Acessibilidade: " + t);
                }
            }
        });
        row2.addView(acc);

        Button tryA = new Button(this);
        tryA.setText("2) Testar passo automático");
        tryA.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!AutoSettingsService.request()) {
                    Log.add(MainActivity.this, "Ative primeiro 'Hora Fix' em Acessibilidade.");
                }
            }
        });
        row2.addView(tryA);

        ScrollView sv = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(13);
        sv.addView(logView);
        root.addView(sv);

        setContentView(root);
    }

    private void render() {
        boolean secure = checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
        boolean setTime = checkCallingOrSelfPermission("android.permission.SET_TIME")
                == PackageManager.PERMISSION_GRANTED;
        status.setText("Hora do aparelho: " + DateFormat.format("dd/MM/yyyy HH:mm:ss", new Date())
                + (SyncService.clockLooksWrong() ? "  (ERRADA)" : "  (ok)")
                + "\nRoot: " + rootState
                + "\nSET_TIME: " + (setTime ? "concedida" : "não")
                + "\nWRITE_SECURE_SETTINGS: " + (secure ? "concedida" : "não")
                + "\nAcessibilidade: " + (AutoSettingsService.isActive() ? "ATIVA" : "desativada")
                + (secure || setTime || AutoSettingsService.isActive() ? "" :
                "\n\nSem root nem computador: toque em 1) e ative 'Hora Fix' na lista de Acessibilidade.")
                + "\n\nRegistro:");
        logView.setText(Log.read(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }
}
