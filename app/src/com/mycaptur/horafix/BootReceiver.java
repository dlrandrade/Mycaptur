package com.mycaptur.horafix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Se o relógio já está certo não há nada a fazer (evita acordar o serviço à toa
        // a cada mudança de rede). Ano < 2025 = data de fábrica/2007 = precisa corrigir.
        String action = intent == null ? null : intent.getAction();
        if (android.net.ConnectivityManager.CONNECTIVITY_ACTION.equals(action)
                && !SyncService.clockLooksWrong()) {
            return;
        }
        Log.add(context, "Disparo: " + action);
        context.startService(new Intent(context, SyncService.class));
    }
}
