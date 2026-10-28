package com.rddmwmddr.adcleaner;

import android.os.Binder;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Shizuku UserService; commands execute under the approved shell identity. */
public final class PrivilegedService extends IPrivilegedService.Stub {
    @Override public String run(String command) {
        try {
            Process p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder(); String line; boolean truncated = false;
            while ((line = in.readLine()) != null) {
                if (out.length() + line.length() + 1 <= 250_000) out.append(line).append('\n');
                else if (!truncated) { out.append("[output truncated]\n"); truncated = true; }
            }
            int code = p.waitFor(); return "exit=" + code + "\n" + out;
        } catch (Exception e) { return "错误：" + e.getMessage(); }
    }
    @Override public void destroy() { System.exit(0); }
}
