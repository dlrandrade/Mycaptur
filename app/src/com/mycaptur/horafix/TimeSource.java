package com.mycaptur.horafix;

import android.content.Context;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

/**
 * Obtém a hora real da internet SEM depender do relógio do aparelho:
 * NTP via UDP (não usa certificado) e, como reserva, o cabeçalho "Date" de uma
 * resposta HTTP simples (sem HTTPS, porque com a data de 2007 o certificado seria rejeitado).
 */
final class TimeSource {
    private static final String[] NTP_HOSTS = {
            "time.google.com", "pool.ntp.org", "time.cloudflare.com", "a.st1.ntp.br", "time.windows.com"
    };
    private static final String[] HTTP_URLS = {
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://www.google.com/",
            "http://clients3.google.com/generate_204"
    };

    private TimeSource() {}

    /** @return epoch em ms (UTC) ou -1 se nenhuma fonte respondeu. */
    static long fetch(Context ctx) {
        for (String host : NTP_HOSTS) {
            long t = ntp(host);
            if (t > 0) {
                Log.add(ctx, "Hora obtida via NTP (" + host + ")");
                return t;
            }
        }
        for (String u : HTTP_URLS) {
            long t = httpDate(u);
            if (t > 0) {
                Log.add(ctx, "Hora obtida via HTTP (" + u + ")");
                return t;
            }
        }
        return -1;
    }

    private static long ntp(String host) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(4000);
            InetAddress addr = InetAddress.getByName(host);
            byte[] buf = new byte[48];
            buf[0] = 0x1B; // LI=0, VN=3, Mode=3 (cliente)
            long t0 = System.nanoTime();
            s.send(new DatagramPacket(buf, buf.length, addr, 123));
            DatagramPacket resp = new DatagramPacket(new byte[48], 48);
            s.receive(resp);
            long rtt = (System.nanoTime() - t0) / 1000000L;
            byte[] b = resp.getData();
            long secs = 0;
            for (int i = 40; i < 44; i++) secs = (secs << 8) | (b[i] & 0xFF);
            long frac = 0;
            for (int i = 44; i < 48; i++) frac = (frac << 8) | (b[i] & 0xFF);
            if (secs == 0) return -1;
            long ms = (secs - 2208988800L) * 1000L + (frac * 1000L >> 32);
            return ms + rtt / 2;
        } catch (Exception e) {
            return -1;
        } finally {
            if (s != null) s.close();
        }
    }

    private static long httpDate(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("HEAD");
            c.getResponseCode();
            long d = c.getDate();
            return d > 0 ? d : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
