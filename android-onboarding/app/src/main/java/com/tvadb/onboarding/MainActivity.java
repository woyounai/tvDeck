package com.tvadb.onboarding;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.nayuki.qrcodegen.QrCode;

public final class MainActivity extends Activity {
    private static final int CYAN = Color.rgb(30, 231, 242);
    private static final int MAGENTA = Color.rgb(220, 57, 189);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService probe = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView detail;
    private ImageView qrView;
    private String displayedEndpoint = "";
    private boolean running;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setBackgroundColor(Color.rgb(9, 11, 22));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(36), dp(24), dp(36), dp(24));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(23, 17, 52), Color.rgb(9, 11, 22), Color.rgb(12, 35, 46)});
        root.setBackground(background);

        TextView title = label(getString(R.string.title), 30, CYAN);
        title.setLetterSpacing(0.14f);
        root.addView(title);
        TextView intro = label(getString(R.string.intro), 17, Color.rgb(173, 202, 218));
        root.addView(intro);
        View line = new View(this);
        line.setBackgroundColor(MAGENTA);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(dp(430), dp(2));
        lineParams.setMargins(0, dp(14), 0, dp(22));
        root.addView(line, lineParams);

        status = label(getString(R.string.checking), 24, Color.WHITE);
        root.addView(status);
        detail = label("", 17, Color.rgb(173, 202, 218));
        detail.setMaxWidth(dp(850));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-2, -2);
        detailParams.setMargins(0, dp(14), 0, dp(18));
        root.addView(detail, detailParams);

        qrView = new ImageView(this);
        qrView.setContentDescription(getString(R.string.qr_description));
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setPadding(dp(12), dp(12), dp(12), dp(12));
        qrView.setVisibility(View.GONE);
        root.addView(qrView, new LinearLayout.LayoutParams(dp(278), dp(278)));
        setContentView(root);
    }

    @Override public void onStart() {
        super.onStart();
        running = true;
        handler.post(refresh);
    }

    @Override public void onStop() {
        running = false;
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    @Override public void onDestroy() {
        probe.shutdownNow();
        super.onDestroy();
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running) return;
            probe.execute(() -> {
                String ip = localAddress();
                int port = adbPort();
                boolean ready = ip != null && port > 0 && isPortOpen(port);
                handler.post(() -> {
                    if (!running) return;
                    String endpoint = ready ? ip + ":" + port : "";
                    if (!endpoint.equals(displayedEndpoint)) {
                        displayedEndpoint = endpoint;
                        if (ready) showCode(ip, port);
                        else qrView.setVisibility(View.GONE);
                    }
                    if (ready) {
                        status.setText(R.string.ready);
                        detail.setText(getString(R.string.ready_detail, endpoint));
                    } else {
                        status.setText(ip == null ? R.string.no_network : R.string.waiting_adb);
                        detail.setText(ip == null
                                ? R.string.no_network_detail : R.string.waiting_adb_detail);
                    }
                    handler.postDelayed(this, 2000);
                });
            });
        }
    };

    private void showCode(String ip, int port) {
        String payload = "tvadb://connect?host=" + ip + "&port=" + port;
        QrCode code = QrCode.encodeText(payload, QrCode.Ecc.MEDIUM);
        int scale = Math.max(1, 250 / code.size);
        int margin = 4;
        int size = (code.size + margin * 2) * scale;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        for (int y = 0; y < code.size; y++) {
            for (int x = 0; x < code.size; x++) {
                if (!code.getModule(x, y)) continue;
                for (int dy = 0; dy < scale; dy++)
                    for (int dx = 0; dx < scale; dx++)
                        bitmap.setPixel((x + margin) * scale + dx, (y + margin) * scale + dy, Color.BLACK);
            }
        }
        qrView.setImageBitmap(bitmap);
        qrView.setVisibility(View.VISIBLE);
    }

    private static String localAddress() {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = iface.getName();
                if (!iface.isUp() || !(name.startsWith("wlan") || name.startsWith("eth"))) continue;
                for (java.net.InetAddress address : Collections.list(iface.getInetAddresses())) {
                    if (!(address instanceof Inet4Address)) continue;
                    if (!address.isLoopbackAddress() && !address.isLinkLocalAddress())
                        return address.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static int adbPort() {
        for (String key : new String[]{"service.adb.tcp.port", "persist.adb.tcp.port"}) {
            try {
                Process process = new ProcessBuilder("getprop", key).start();
                byte[] bytes = new byte[16];
                int count = process.getInputStream().read(bytes);
                process.destroy();
                if (count > 0) {
                    int port = Integer.parseInt(new String(bytes, 0, count).trim());
                    if (port > 0 && port <= 65535) return port;
                }
            } catch (Exception ignored) { }
        }
        return 5555;
    }

    private static boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 350);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
