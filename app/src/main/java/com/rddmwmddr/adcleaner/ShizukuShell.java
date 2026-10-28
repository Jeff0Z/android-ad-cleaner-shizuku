package com.rddmwmddr.adcleaner;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import rikka.shizuku.Shizuku;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ShizukuShell {
    private static volatile IPrivilegedService service;
    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { service = IPrivilegedService.Stub.asInterface(binder); }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };
    static String connect(Context context) {
        if (service != null) return "";
        try {
            if (!Shizuku.pingBinder()) return "Shizuku 服务未运行，请先启动 Shizuku。";
            Shizuku.bindUserService(new Shizuku.UserServiceArgs(new ComponentName(context, PrivilegedService.class))
                    .processNameSuffix("shell")
                    .version(2)
                    .daemon(true)
                    .tag("ad-cleaner-shell-v1"), CONNECTION);
            return "";
        } catch (Throwable e) {
            return "Shizuku 特权服务启动失败：" + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }
    static String run(Context context, String command) {
        if (!Shizuku.pingBinder()) return "Shizuku 服务未运行，请先启动 Shizuku。";
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return "尚未获得 Shizuku 授权。";
        try {
            connect(context);
            for (int i=0; service == null && i<30; i++) Thread.sleep(100);
            if (service == null) return "Shizuku 特权服务连接超时，请重试。";
            return service.run(command);
        } catch (Throwable e) { return "Shizuku 执行失败：" + e.getClass().getSimpleName() + " " + e.getMessage(); }
    }
}
