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
                + (secure || setTime ? "" :
                "\n\nSem root, rode UMA vez no computador (USB/ADB):\nadb shell pm grant com.mycaptur.horafix android.permission.WRITE_SECURE_SETTINGS")
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
