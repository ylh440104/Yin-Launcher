package net.kdt.pojavlaunch.utils;

import net.kdt.pojavlaunch.Architecture;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.Log;

import com.oracle.dalvik.VMLauncher;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.GLInfoUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class ZalithUtils {
    private static final String TAG = "ZalithChain";

    public static String DIR_NATIVE_LIB;
    public static String rendererId = "opengles3";
    public static String rendererLibrary = "libgl4es_114.so";
    public static int ramAllocationMb = 2048;
    public static boolean bigCoreAffinity = false;
    public static boolean forceVsync = true;
    public static boolean dumpShaders = false;
    public static boolean zinkPreferSystemDriver = false;
    public static boolean vsyncInZink = false;
    public static boolean zink = false;
    public static float resolutionRatio = 1.0f;
    public static String gameHome;
    public static String gameDir;
    public static File cacheDir;
    public static File dataDir;
    public static String accountUsername = "Player";
    public static String accessToken = "0";
    public static String uuid = "0";
    public static String versionName = "unknown";
    public static String clientmodname = "YinLauncher";

    public static void launchWithUtils(
            final Context activity,
            final String jreArch,
            String jreHome,
            final String gameDirectory,
            final List<String> JVMArgs,
            final String userArgsString,
            final Runnable onExit
    ) {
        try {
            DIR_NATIVE_LIB = Tools.NATIVE_LIB_DIR;
            gameDir = gameDirectory;
            JREUtils.redirectAndPrintJRELog();

            try { JREUtils.chdir(gameDirectory); } catch (Throwable ignored) {}
            relocateLibPath(jreArch, jreHome);
            initLdLibraryPath(jreHome);
            setEnv(jreHome);
            initJavaRuntime(jreHome);
            initGraphicAndSoundEngine();

            int exitCode = launchJavaVM(jreHome, gameDirectory, JVMArgs, userArgsString);
            if (onExit != null) onExit.run();
            Log.i(TAG, "chain exit: " + exitCode);
        } catch (Throwable t) {
            Log.e(TAG, "launch failed", t);
            Logger.appendToLog("launch failed: " + Log.getStackTraceString(t) + "\n");
            if (onExit != null) onExit.run();
        }
    }

    public static void relocateLibPath(String jreArch, String jreHome) {
        String JRE_ARCHITECTURE = jreArch == null ? "arm64-v8a" : jreArch;
        if (Architecture.archAsInt(JRE_ARCHITECTURE) == Architecture.ARCH_X86) {
            JRE_ARCHITECTURE = "i386/i486/i586";
        }
        for (String arch : JRE_ARCHITECTURE.split("/")) {
            File f = new File(jreHome, "lib/" + arch);
            if (f.exists() && f.isDirectory()) {
                Tools.DIRNAME_HOME_JRE = "lib/" + arch;
            }
        }
        String libName = Architecture.is64BitsDevice() ? "lib64" : "lib";
        StringBuilder ldLibraryPath = new StringBuilder();
        ldLibraryPath.append(jreHome)
                .append("/").append(Tools.DIRNAME_HOME_JRE)
                .append("/jli:").append(jreHome).append("/").append(Tools.DIRNAME_HOME_JRE)
                .append(":");
        ldLibraryPath.append("/system/").append(libName).append(":")
                .append("/vendor/").append(libName).append(":")
                .append("/vendor/").append(libName).append("/hw:");
        ldLibraryPath.append(DIR_NATIVE_LIB);
        JREUtils.LD_LIBRARY_PATH = ldLibraryPath.toString();
    }

    private static void initLdLibraryPath(String jreHome) {
        File serverFile = new File(jreHome + "/" + Tools.DIRNAME_HOME_JRE + "/server/libjvm.so");
        JREUtils.jvmLibraryPath = jreHome + "/" + Tools.DIRNAME_HOME_JRE + "/" + (serverFile.exists() ? "server" : "client");
        Log.d("DynamicLoader", "Base LD_LIBRARY_PATH: " + JREUtils.LD_LIBRARY_PATH);
        Log.d("DynamicLoader", "Internal LD_LIBRARY_PATH: " + JREUtils.jvmLibraryPath + ":" + JREUtils.LD_LIBRARY_PATH);
        JREUtils.setLdLibraryPath(JREUtils.jvmLibraryPath + ":" + JREUtils.LD_LIBRARY_PATH);
    }

    public static void initJavaRuntime(String jreHome) {
        JREUtils.dlopen(JREUtils.findInLdLibPath("libjli.so"));
        if (!JREUtils.dlopen("libjvm.so")) {
            Log.w("DynamicLoader", "Failed to load with no path, trying with full path");
            JREUtils.dlopen(JREUtils.jvmLibraryPath + "/libjvm.so");
        }
        JREUtils.dlopen(JREUtils.findInLdLibPath("libverify.so"));
        JREUtils.dlopen(JREUtils.findInLdLibPath("libjava.so"));
        JREUtils.dlopen(JREUtils.findInLdLibPath("libnet.so"));
        JREUtils.dlopen(JREUtils.findInLdLibPath("libnio.so"));
        JREUtils.dlopen(JREUtils.findInLdLibPath("libawt.so"));
        JREUtils.dlopen(JREUtils.findInLdLibPath("libawt_headless.so"));
        JREUtils.dlopen(JREUtils.findInLdLibPath("libfreetype.so"));
        JREUtils.dlopen(JREUtils.findInLdLibPath("libfontmanager.so"));
        for (File f : JREUtils.locateLibs(new File(jreHome, Tools.DIRNAME_HOME_JRE))) {
            JREUtils.dlopen(f.getAbsolutePath());
        }
    }

    private static void initGraphicAndSoundEngine() {
        JREUtils.dlopen(DIR_NATIVE_LIB + "/libopenal.so");
        String rendererLib = loadGraphicsLibrary();
        if (rendererLib != null) {
            if (!JREUtils.dlopen(rendererLib) && !JREUtils.dlopen(JREUtils.findInLdLibPath(rendererLib))) {
                Log.e("RENDER_LIBRARY", "Failed to load renderer " + rendererLib);
            }
        }
    }

    public static String loadGraphicsLibrary() {
        return rendererLibrary;
    }

    private static void setEnv(String jreHome) throws Throwable {
        Map<String, String> envMap = new ArrayMap<>();
        setJavaEnv(envMap, jreHome);
        setRendererEnv(envMap);
        setCustomEnv(envMap);
        for (Map.Entry<String, String> env : envMap.entrySet()) {
            Logger.appendToLog("Added custom env: " + env.getKey() + "=" + env.getValue() + "\n");
            try {
                Os.setenv(env.getKey(), env.getValue(), true);
            } catch (NullPointerException | ErrnoException exception) {
                Log.e("JREUtils", exception.toString());
            }
        }
    }

    private static void setJavaEnv(Map<String, String> envMap, String jreHome) {
        envMap.put("POJAV_NATIVEDIR", DIR_NATIVE_LIB);
        envMap.put("JAVA_HOME", jreHome);
        envMap.put("HOME", gameHome);
        envMap.put("TMPDIR", cacheDir.getAbsolutePath());
        envMap.put("LD_LIBRARY_PATH", JREUtils.LD_LIBRARY_PATH);
        envMap.put("PATH", jreHome + "/bin:" + Os.getenv("PATH"));
        envMap.put("FORCE_VSYNC", String.valueOf(forceVsync));
        envMap.put("AWTSTUB_WIDTH", Integer.toString(org.lwjgl.glfw.CallbackBridge.windowWidth > 0 ? org.lwjgl.glfw.CallbackBridge.windowWidth : org.lwjgl.glfw.CallbackBridge.physicalWidth));
        envMap.put("AWTSTUB_HEIGHT", Integer.toString(org.lwjgl.glfw.CallbackBridge.windowHeight > 0 ? org.lwjgl.glfw.CallbackBridge.windowHeight : org.lwjgl.glfw.CallbackBridge.physicalHeight));
        if (dumpShaders) envMap.put("LIBGL_VGPU_DUMP", "1");
        if (zinkPreferSystemDriver) envMap.put("POJAV_ZINK_PREFER_SYSTEM_DRIVER", "1");
        if (vsyncInZink) envMap.put("POJAV_VSYNC_IN_ZINK", "1");
        if (bigCoreAffinity) envMap.put("POJAV_BIG_CORE_AFFINITY", "1");
    }

    private static void setRendererEnv(Map<String, String> envMap) {
        if (rendererId.startsWith("opengles2")) {
            envMap.put("LIBGL_ES", "2");
            envMap.put("LIBGL_MIPMAP", "3");
            envMap.put("LIBGL_NOERROR", "1");
            envMap.put("LIBGL_NOINTOVLHACK", "1");
            envMap.put("LIBGL_NORMALIZE", "1");
        }
        String eglName = null;
        if (rendererId.equals("opengles3_ltw")) eglName = "libltw.so";
        if (eglName != null) envMap.put("POJAVEXEC_EGL", eglName);
        envMap.put("POJAV_RENDERER", rendererId);
        if (!rendererId.startsWith("opengles")) {
            envMap.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            envMap.put("MESA_GLSL_CACHE_DIR", cacheDir.getAbsolutePath());
            envMap.put("force_glsl_extensions_warn", "true");
            envMap.put("allow_higher_compat_version", "true");
            envMap.put("allow_glsl_extension_directive_midshader", "true");
            envMap.put("LIB_MESA_NAME", loadGraphicsLibrary());
        }
        if (!envMap.containsKey("LIBGL_ES")) {
            int glesMajor = GLInfoUtils.getGlInfo().glesMajorVersion;
            Log.i("glesDetect", "GLES version detected: " + glesMajor);
            if (glesMajor < 3) {
                envMap.put("LIBGL_ES", "2");
            } else if (rendererId.startsWith("opengles")) {
                envMap.put("LIBGL_ES", rendererId.replace("opengles", "").replace("_5", ""));
            } else {
                envMap.put("LIBGL_ES", "3");
            }
        }
    }

    private static void setCustomEnv(Map<String, String> envMap) throws Throwable {
        File customEnvFile = new File(gameHome, "custom_env.txt");
        if (customEnvFile.exists() && customEnvFile.isFile()) {
            BufferedReader reader = new BufferedReader(new FileReader(customEnvFile));
            String line;
            while ((line = reader.readLine()) != null) {
                int index = line.indexOf("=");
                envMap.put(line.substring(0, index), line.substring(index + 1));
            }
            reader.close();
        }
    }

    private static int launchJavaVM(
            String runtimeHome,
            String gameDirectory,
            final List<String> JVMArgs,
            final String userArgsString
    ) {
        List<String> userArgs = getJavaArgs(runtimeHome, userArgsString);
        purgeArg(userArgs, "-Xms");
        purgeArg(userArgs, "-Xmx");
        purgeArg(userArgs, "-d32");
        purgeArg(userArgs, "-d64");
        purgeArg(userArgs, "-Xint");
        purgeArg(userArgs, "-XX:+UseTransparentHugePages");
        purgeArg(userArgs, "-XX:+UseLargePagesInMetaspace");
        purgeArg(userArgs, "-XX:+UseLargePages");
        purgeArg(userArgs, "-Dorg.lwjgl.opengl.libname");
        purgeArg(userArgs, "-Dorg.lwjgl.freetype.libname");
        purgeArg(userArgs, "-XX:ActiveProcessorCount");
        userArgs.add("-Xms" + ramAllocationMb + "M");
        userArgs.add("-Xmx" + ramAllocationMb + "M");
        userArgs.add("-Dorg.lwjgl.opengl.libname=" + loadGraphicsLibrary());
        userArgs.add("-Dorg.lwjgl.freetype.libname=" + DIR_NATIVE_LIB + "/libfreetype.so");
        userArgs.add("-XX:ActiveProcessorCount=" + java.lang.Runtime.getRuntime().availableProcessors());
        userArgs.addAll(JVMArgs);
        for (int i = 0; i < userArgs.size(); i++) {
            String arg = userArgs.get(i);
            if (arg.startsWith("--accessToken")) {
                i += 1;
            }
            Logger.appendToLog("JVMArg: " + arg + "\n");
        }
        try { JREUtils.setupExitMethod(null); } catch (Throwable ignored) {}
        try { JREUtils.initializeHooks(); } catch (Throwable ignored) {}
        JREUtils.chdir(gameDirectory);
        userArgs.add(0, "java");
        final int exitCode = VMLauncher.launchJVM(userArgs.toArray(new String[0]));
        Logger.appendToLog("Java Exit code: " + exitCode + "\n");
        JREUtils.lastExitCode = exitCode;
        return exitCode;
    }

    public static List<String> getJavaArgs(String runtimeHome, String userArgumentsString) {
        List<String> userArguments = parseJavaArguments(userArgumentsString);
        String resolvFile = new File(dataDir, "resolv.conf").getAbsolutePath();
        ArrayList<String> overridableArguments = new ArrayList<>(java.util.Arrays.asList(
                "-Djava.home=" + runtimeHome,
                "-Djava.io.tmpdir=" + cacheDir.getAbsolutePath(),
                "-Djna.boot.library.path=" + DIR_NATIVE_LIB,
                "-Duser.home=" + gameHome,
                "-Duser.language=" + System.getProperty("user.language"),
                "-Dos.name=Linux",
                "-Dos.version=Android-" + Build.VERSION.RELEASE,
                "-Dpojav.path.minecraft=" + (gameDir != null ? gameDir : gameHome + "/.minecraft"),
                "-Dpojav.path.private.account=" + dataDir + "/accounts",
                "-Duser.timezone=" + TimeZone.getDefault().getID(),
                "-Dorg.lwjgl.vulkan.libname=libvulkan.so",
                "-Dglfwstub.windowWidth=" + Tools.getDisplayFriendlyRes(Tools.currentDisplayMetrics.widthPixels, resolutionRatio),
                "-Dglfwstub.windowHeight=" + Tools.getDisplayFriendlyRes(Tools.currentDisplayMetrics.heightPixels, resolutionRatio),
                "-Dglfwstub.initEgl=false",
                "-Dext.net.resolvPath=" + resolvFile,
                "-Dlog4j2.formatMsgNoLookups=true",
                "-Dnet.minecraft.clientmodname=" + clientmodname,
                "-Dfml.earlyprogresswindow=false",
                "-Dloader.disable_forked_guis=true",
                "-Djdk.lang.Process.launchMechanism=FORK",
                "-Dsodium.checks.issue2561=false"
        ));
        List<String> additionalArguments = new ArrayList<>();
        for (String arg : overridableArguments) {
            String strippedArg = arg.substring(0, arg.indexOf('='));
            boolean add = true;
            for (String uarg : userArguments) {
                if (uarg.startsWith(strippedArg)) {
                    add = false;
                    break;
                }
            }
            if (add) additionalArguments.add(arg);
            else Log.i("ArgProcessor", "Arg skipped: " + arg);
        }
        userArguments.addAll(additionalArguments);
        return userArguments;
    }

    public static ArrayList<String> parseJavaArguments(String args) {
        ArrayList<String> parsedArguments = new ArrayList<>(0);
        args = args.trim().replace(" ", "");
        String[] separators = new String[]{"-XX:-", "-XX:+", "-XX:", "--", "-D", "-X", "-javaagent:", "-verbose"};
        for (String prefix : separators) {
            while (true) {
                int start = args.indexOf(prefix);
                if (start == -1) break;
                int end = -1;
                for (String separator : separators) {
                    int tempEnd = args.indexOf(separator, start + prefix.length());
                    if (tempEnd == -1) continue;
                    if (end == -1) {
                        end = tempEnd;
                        continue;
                    }
                    end = Math.min(end, tempEnd);
                }
                if (end == -1) end = args.length();
                String parsedSubString = args.substring(start, end);
                args = args.replace(parsedSubString, "");
                if (parsedSubString.indexOf('=') == parsedSubString.lastIndexOf('=')) {
                    int arraySize = parsedArguments.size();
                    if (arraySize > 0) {
                        String lastString = parsedArguments.get(arraySize - 1);
                        if (lastString.charAt(lastString.length() - 1) == ',' ||
                                parsedSubString.contains(",")) {
                            parsedArguments.set(arraySize - 1, lastString + parsedSubString);
                            continue;
                        }
                    }
                    parsedArguments.add(parsedSubString);
                } else Log.w("JAVA ARGS PARSER", "Removed improper arguments: " + parsedSubString);
            }
        }
        return parsedArguments;
    }

    private static void purgeArg(List<String> argList, String argStart) {
        Iterator<String> args = argList.iterator();
        while (args.hasNext()) {
            String arg = args.next();
            if (arg.startsWith(argStart)) args.remove();
        }
    }
}