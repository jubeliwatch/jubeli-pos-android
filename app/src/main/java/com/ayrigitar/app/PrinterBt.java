package com.ayrigitar.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/* Printer struk thermal Bluetooth (mis. iWare 58 mm): Bluetooth klasik SPP + perintah ESC/POS.
   Isi struk disusun halaman admin (Layanan.strukThermal) sebagai daftar baris; di sini hanya diubah jadi byte dan dikirim. */
final class PrinterBt {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int MAKS_BARIS = 400, MAKS_HURUF = 64;

    private PrinterBt() { }

    /* baris: [{ t: teks, a: 'c'|'l', b: tebal, besar: huruf 2x }] */
    static byte[] dariBaris(JSONArray baris) throws JSONException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        tulis(o, 0x1B, 0x40);                                   /* ESC @ : mulai ulang printer */
        int n = Math.min(baris.length(), MAKS_BARIS);
        for (int i = 0; i < n; i++) {
            JSONObject b = baris.getJSONObject(i);
            tulis(o, 0x1B, 0x61, "c".equals(b.optString("a")) ? 1 : 0);   /* ESC a : rata */
            tulis(o, 0x1B, 0x45, b.optBoolean("b") ? 1 : 0);              /* ESC E : tebal */
            tulis(o, 0x1D, 0x21, b.optBoolean("besar") ? 0x11 : 0);       /* GS ! : ukuran huruf */
            byte[] t = ascii(b.optString("t", ""));
            o.write(t, 0, t.length);
            o.write(0x0A);
        }
        tulis(o, 0x1B, 0x61, 0);
        tulis(o, 0x1B, 0x45, 0);
        tulis(o, 0x1D, 0x21, 0);
        tulis(o, 0x1B, 0x64, 4);                                /* ESC d 4 : dorong kertas supaya bisa disobek */
        return o.toByteArray();
    }

    /* printer thermal murah hanya mengenal huruf ASCII; selain itu jadi '?' */
    private static byte[] ascii(String s) {
        int n = Math.min(s.length(), MAKS_HURUF);
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            b[i] = (byte) (c >= 0x20 && c < 0x7F ? c : '?');
        }
        return b;
    }

    private static void tulis(ByteArrayOutputStream o, int... b) {
        for (int x : b) o.write(x);
    }

    /* sambung, kirim, tutup. Dicoba 3 cara karena printer murah sering tidak mengumumkan layanan SPP dengan benar */
    static void kirim(BluetoothAdapter ad, String alamat, byte[] data) throws IOException {
        BluetoothDevice dev = ad.getRemoteDevice(alamat);
        BluetoothSocket sk = sambung(dev);
        try {
            OutputStream o = sk.getOutputStream();
            for (int i = 0; i < data.length; i += 512) {
                o.write(data, i, Math.min(512, data.length - i));
                o.flush();
                tidur(30);
            }
            /* beri waktu printer menerima semua data sebelum sambungan diputus */
            tidur(500 + data.length / 8);
        } finally {
            try { sk.close(); } catch (IOException e) { /* abaikan */ }
        }
    }

    private static BluetoothSocket sambung(BluetoothDevice dev) throws IOException {
        IOException terakhir = null;
        for (int cara = 0; cara < 3; cara++) {
            BluetoothSocket sk = null;
            try {
                if (cara == 0) sk = dev.createRfcommSocketToServiceRecord(SPP);
                else if (cara == 1) sk = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                else sk = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                sk.connect();
                return sk;
            } catch (IOException e) {
                terakhir = e;
            } catch (ReflectiveOperationException e) {
                terakhir = new IOException(e);
            }
            if (sk != null) try { sk.close(); } catch (IOException e) { /* abaikan */ }
        }
        throw terakhir;
    }

    private static void tidur(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
