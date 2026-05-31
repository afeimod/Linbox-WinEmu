package org.github.ewt45.winemulator.emu;

import static android.system.Os.getenv;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class X11Loader {
    public static void main(String[] args) {
        String apkPath = getenv("XSERVER_APK_PATH");
        System.out.println("X11Loader 测试输出 接受参数=" + Arrays.toString(args) +"   apkpath=" + apkPath);

        try {
            Class<?> targetClass = Class.forName("com.termux.x11.CmdEntryPoint", true,
                    new dalvik.system.PathClassLoader(apkPath, null, ClassLoader.getSystemClassLoader()));
            targetClass.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (Throwable e) {
            e.printStackTrace(System.err);
        }
    }
}
