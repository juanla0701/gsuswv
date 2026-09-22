package com.juanla0701.futuresignal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.WebView;
import androidx.core.app.NotificationCompat;
import java.lang.ref.WeakReference;

/**
 * 왜 이렇게 만들었나:
 * Android WebView는 화면(Activity)이 안 보이면 JS 타이머(setInterval)가 멈추는 게 기본 동작이라,
 * Foreground Service만 띄운다고 기존 app.js의 폴링이 계속 도는 게 아니다.
 * 그래서 이 서비스 안에 "화면에 그리지 않는" WebView를 하나 더 띄워서 기존 index.html을
 * 그대로 로드하고, 그 WebView 안에서 기존 신호 감시/자가학습 JS가 계속 실행되게 한다.
 *
 * 같은 앱 프로세스 안의 WebView들은 기본적으로 localStorage를 공유하므로
 * (별도 data directory를 지정하지 않는 한), 여기서 쌓인 State/기록/학습 데이터는
 * 사용자가 앱(Activity)을 다시 열면 그대로 이어서 보인다.
 *
 * 중요(점검 후 수정): 앱이 화면에 켜져 있을 때는 Activity 쪽 WebView가 이미 폴링 중이므로,
 * 이 서비스의 WebView는 "앱이 백그라운드일 때만" 폴링하도록 일시정지/재개를 맞춰준다.
 * 이걸 안 하면 화면 켜진 상태에서도 두 WebView가 동시에 Binance API를 호출해서
 * 요청이 2배가 되고, 같은 신호가 중복 기록/중복 알림될 수 있다.
 */
public class MonitorForegroundService extends Service {

    static final String CHANNEL_ID = "signal_monitor_channel";
    static final int NOTIF_ID = 1001;
    private static final String PREFS = "bg_monitor_prefs";
    private static final String KEY_SHOULD_RUN = "should_run";

    // WakeLock 타임아웃/갱신 주기: 타임아웃보다 여유 있게 미리 갱신해서 장시간(수시간) 감시 중
    // WakeLock이 만료되어 CPU가 슬립 모드로 빠지는 일이 없게 한다.
    private static final long WAKE_LOCK_TIMEOUT_MS = 40 * 60 * 1000L; // 40분
    private static final long WAKE_LOCK_RENEW_INTERVAL_MS = 30 * 60 * 1000L; // 30분마다 갱신

    public static volatile boolean isRunning = false;
    private static volatile WeakReference<MonitorForegroundService> activeInstance;

    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable wakeLockRenewRunnable = new Runnable() {
        @Override
        public void run() {
            acquireWakeLock(); // setReferenceCounted(false) 상태라 재호출 시 타임아웃이 다시 연장됨
            handler.postDelayed(this, WAKE_LOCK_RENEW_INTERVAL_MS);
        }
    };

    static void userRequestedStop(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_SHOULD_RUN, false).apply();
    }

    private static boolean shouldRun(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SHOULD_RUN, false);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // BackgroundMonitorPlugin의 handleOnResume()/handleOnPause()에서 호출된다.
    static void notifyAppForeground() {
        MonitorForegroundService svc = activeInstance != null ? activeInstance.get() : null;
        if (svc != null) svc.pauseBackgroundWebView();
    }

    static void notifyAppBackground() {
        MonitorForegroundService svc = activeInstance != null ? activeInstance.get() : null;
        if (svc != null) svc.resumeBackgroundWebView();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = new WeakReference<>(this);
        createChannelIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        prefs(this).edit().putBoolean(KEY_SHOULD_RUN, true).apply();
        startForeground(NOTIF_ID, buildNotification());
        acquireWakeLock();
        handler.removeCallbacks(wakeLockRenewRunnable);
        handler.postDelayed(wakeLockRenewRunnable, WAKE_LOCK_RENEW_INTERVAL_MS);
        ensureWebView();
        // 사용자는 항상 앱 화면을 보면서 이 서비스를 켜므로(토글 버튼), 시작 직후에는 앱이
        // 포그라운드 상태라고 가정하고 일단 일시정지해둔다. 실제로 앱이 백그라운드로 가는
        // 순간(BackgroundMonitorPlugin.handleOnPause) resumeBackgroundWebView()가 재개시킨다.
        pauseBackgroundWebView();
        isRunning = true;
        return START_STICKY; // 시스템이 프로세스를 죽였다가도 리소스 여유가 생기면 재시작 시도
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        handler.removeCallbacks(wakeLockRenewRunnable);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        releaseWakeLock();
        activeInstance = null;
        super.onDestroy();
    }

    // 사용자가 "최근 앱" 목록에서 카드를 스와이프로 지웠을 때 호출된다.
    // (이것은 "정상 종료"가 아니다 — 사용자가 앱 안에서 직접 감시를 끈 경우에만 KEY_SHOULD_RUN이 false가 된다.)
    // 참고: 설정 > 앱 > 강제 종료를 누르면 이 콜백조차 호출되지 않고 OS가 프로세스를 강제로 죽인다.
    // 이건 Android 정책상 앱이 막을 수 없는 부분이라 여기서 해결할 수 없다.
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (shouldRun(this)) {
            Intent restart = new Intent(getApplicationContext(), MonitorForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(restart);
            } else {
                getApplicationContext().startService(restart);
            }
        } else {
            stopSelf();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureWebView() {
        if (webView != null) return;
        WebView wv = new WebView(getApplicationContext());
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        wv.getSettings().setDatabaseEnabled(true);
        // 기존 웹앱을 그대로 로드 (Capacitor 기본 로컬 서버와 동일한 오리진이어야 localStorage 공유됨)
        wv.loadUrl("https://localhost/index.html");
        wv.onResume();
        wv.resumeTimers();
        webView = wv;
    }

    // 앱이 화면에 보이는 동안: Activity 쪽 WebView가 이미 폴링 중이므로 여기는 멈춰서 중복을 막는다.
    private void pauseBackgroundWebView() {
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
    }

    // 앱이 백그라운드로 감: 여기서부터 이 WebView가 감시를 이어받는다.
    private void resumeBackgroundWebView() {
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FutureSignal:MonitorWakeLock");
            wakeLock.setReferenceCounted(false); // acquire()를 여러 번 호출해도 release() 한 번이면 완전히 풀림 + 매번 타임아웃 재연장
        }
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "신호 감시", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("화면이 꺼져 있어도 신호 감시를 계속합니다");
                mgr.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("선물 신호 감시 실행 중")
                .setContentText("백그라운드에서 신호를 계속 감시하고 있습니다")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
