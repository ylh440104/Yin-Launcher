#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdarg.h>
#include <android/log.h>

#define TAG "JvmBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Java 异常除了打 logcat，还落盘一份：logcat 会被系统噪音冲掉 */
static char g_dumpPath[512] = {0};

static void dump_line(const char *line) {
    if (g_dumpPath[0] == 0) return;
    FILE *f = fopen(g_dumpPath, "a");
    if (f == NULL) return;
    fputs(line, f);
    fputc('\n', f);
    fclose(f);
}

static void dump_fmt(const char *fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    dump_line(buf);
}

typedef jint (*CreateJavaVM_t)(JavaVM **, void **, void *);

static JavaVM *g_vm = NULL;

/* 由 Kotlin 侧传入、在专用线程里执行的全部参数 */
typedef struct {
    char *libjvmPath;
    int optionCount;
    char **options;
    char *nativeLibDir;
    char *mainClass;
    int argCount;
    char **mainArgs;
    int cpCount;
    char **classpath;

    /* 回传给 Kotlin 的结果 */
    jint vmRc;
    jint mainRc;
    pthread_mutex_t lock;
    pthread_cond_t cond;
    int done;
} LaunchJob;

static char *dup_utf(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    if (c == NULL) return NULL;
    char *r = strdup(c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return r;
}

static char **dup_array(JNIEnv *env, jobjectArray arr, int *countOut) {
    int n = arr == NULL ? 0 : (*env)->GetArrayLength(env, arr);
    char **out = (char **) calloc(n > 0 ? n : 1, sizeof(char *));
    for (int i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        out[i] = dup_utf(env, s);
        (*env)->DeleteLocalRef(env, s);
    }
    *countOut = n;
    return out;
}

static void dump_exception(JNIEnv *e, const char *where) {
    if (!(*e)->ExceptionCheck(e)) return;
    jthrowable t = (*e)->ExceptionOccurred(e);
    (*e)->ExceptionClear(e);

    /* 完整类型 + message + 最内层 cause，ClassNotFound 的真实原因常在 cause 里 */
    jclass tc = (*e)->GetObjectClass(e, t);
    jmethodID tn = (*e)->GetMethodID(e, tc, "getName", "()Ljava/lang/String;");
    jmethodID tm = (*e)->GetMethodID(e, tc, "getMessage", "()Ljava/lang/String;");
    if (tn) {
        jstring n = (jstring) (*e)->CallObjectMethod(e, t, tn);
        if (n) {
            const char *c = (*e)->GetStringUTFChars(e, n, NULL);
            LOGE("[%s] EX %s", where, c ? c : "?"); dump_fmt("[%s] EX %s", where, c ? c : "?");
            (*e)->ReleaseStringUTFChars(e, n, c);
        }
    }
    if (tm) {
        jstring m = (jstring) (*e)->CallObjectMethod(e, t, tm);
        if (m) {
            const char *c = (*e)->GetStringUTFChars(e, m, NULL);
            LOGE("[%s] MSG %s", where, c ? c : "?"); dump_fmt("[%s] MSG %s", where, c ? c : "?");
            (*e)->ReleaseStringUTFChars(e, m, c);
        }
    }

    jclass thClass = (*e)->FindClass(e, "java/lang/Throwable");
    jmethodID getCause = (*e)->GetMethodID(e, thClass, "getCause",
                                           "()Ljava/lang/Throwable;");
    jobject cause = (*e)->CallObjectMethod(e, t, getCause);
    int depth = 0;
    while (cause != NULL && depth < 5) {
        jclass cc = (*e)->GetObjectClass(e, cause);
        jmethodID cn = (*e)->GetMethodID(e, cc, "getName", "()Ljava/lang/String;");
        jmethodID cm = (*e)->GetMethodID(e, cc, "getMessage", "()Ljava/lang/String;");
        jstring cns = cn ? (jstring) (*e)->CallObjectMethod(e, cause, cn) : NULL;
        jstring cms = cm ? (jstring) (*e)->CallObjectMethod(e, cause, cm) : NULL;
        const char *cnc = cns ? (*e)->GetStringUTFChars(e, cns, NULL) : NULL;
        const char *cmc = cms ? (*e)->GetStringUTFChars(e, cms, NULL) : NULL;
        LOGE("[%s] cause[%d] %s: %s", where, depth, cnc ? cnc : "?", cmc ? cmc : "?"); dump_fmt("[%s] cause[%d] %s: %s", where, depth, cnc ? cnc : "?", cmc ? cmc : "?");
        if (cnc) (*e)->ReleaseStringUTFChars(e, cns, cnc);
        if (cmc) (*e)->ReleaseStringUTFChars(e, cms, cmc);
        jobject next = (*e)->CallObjectMethod(e, cause, getCause);
        if ((*e)->ExceptionCheck(e)) (*e)->ExceptionClear(e);
        cause = next;
        depth++;
    }
}

/*
 * 专用线程：CreateJavaVM 和 main 都在这里执行。
 *
 * 之前把两者放在 ART 的工作线程上，JVM 内部线程对象没建立，
 * KnotClient 一执行就在 libjvm 里空指针崩溃（SIGSEGV 0x24）。
 */
// chdir 只能在 native 做：Kotlin 侧 android.system.Os 没有公开 chdir。
// Fabric 用相对路径 ".fabric/remappedJars/..." 打开输出文件，cwd 必须是游戏根目录。
JNIEXPORT jint JNICALL
Java_com_mcbox_launcher_runtime_JvmBridge_nativeChdir(JNIEnv *env, jclass clazz, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return -1;
    int rc = chdir(p);
    LOGI("nativeChdir(%s) = %d", p, rc);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return rc;
}

static void *launch_thread(void *arg) {
    LaunchJob *job = (LaunchJob *) arg;

    /* libpojavexec 的 hookExec() 用
     *   dlsym(RTLD_DEFAULT, "Java_java_lang_UNIXProcess_forkAndExec")
     * 找符号；该符号在 libjava.so 里。RTLD_DEFAULT 只搜全局符号表，
     * 所以 libjava.so 必须在任何 RTLD_LOCAL 加载之前以 RTLD_GLOBAL 进来。
     * 找不到时 hookClass==NULL，RegisterNatives(NULL, ...) 跳地址 0，
     * 这正是 mcbox-launch 线程上 SEGV_MAPERR addr 0x0 的来源。
     */
    {
        const char *extra[] = {"libjava.so", "libnet.so", "libnio.so",
                               "libzip.so", "libjimage.so", "libverify.so"};
        char jp[1024];
        for (int i = 0; i < 6; i++) {
            snprintf(jp, sizeof(jp), "%s/../lib/%s", job->libjvmPath, extra[i]);
            void *eh = dlopen(jp, RTLD_NOW | RTLD_GLOBAL);
            if (eh) {
                LOGI("jre preload ok: %s", extra[i]);
            } else {
                LOGI("jre preload miss: %s (%s)", extra[i], dlerror());
            }
        }
    }
    void *handle = dlopen(job->libjvmPath, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        LOGE("dlopen libjvm failed: %s", dlerror());
        job->vmRc = -10;
        goto finish;
    }
    CreateJavaVM_t create = (CreateJavaVM_t) dlsym(handle, "JNI_CreateJavaVM");
    if (create == NULL) {
        LOGE("dlsym JNI_CreateJavaVM failed: %s", dlerror());
        job->vmRc = -11;
        goto finish;
    }

    JavaVMOption *opts = (JavaVMOption *) calloc(
            job->optionCount > 0 ? job->optionCount : 1, sizeof(JavaVMOption));
    for (int i = 0; i < job->optionCount; i++) {
        opts[i].optionString = job->options[i];
        opts[i].extraInfo = NULL;
    }

    /* LWJGL 的 CallbackBridge.nativeSetUseInputStackQueue 实现在 libpojavexec.so 里，
     * 必须先以 RTLD_GLOBAL 载入，符号才对 System.loadLibrary("lwjgl") 可见。 */
    const char *extraLibs[] = {"libpojavexec.so", "liblwjgl.so", "liblwjgl_opengl.so",
                               "liblwjgl_stb.so", "liblwjgl_tinyfd.so", "libopenal.so",
                               "libfreetype.so", "libgl4es_114.so"};
    for (int i = 0; i < 8; i++) {
        void *h = dlopen(extraLibs[i], RTLD_NOW | RTLD_GLOBAL);
        if (h == NULL) {
            LOGE("preload %s failed: %s", extraLibs[i], dlerror());
        } else {
            LOGI("preload %s ok", extraLibs[i]);
        }
    }

    JavaVMInitArgs vargs;
    vargs.version = JNI_VERSION_1_6;
    vargs.nOptions = job->optionCount;
    vargs.options = opts;
    vargs.ignoreUnrecognized = JNI_FALSE;

    JNIEnv *jenv = NULL;
    job->vmRc = create(&g_vm, (void **) &jenv, &vargs);
    LOGI("JNI_CreateJavaVM rc=%d vm=%p env=%p", job->vmRc, (void *) g_vm, (void *) jenv);
    if (job->vmRc != 0) goto finish;

    /* 建 URLClassLoader */
    jclass fileClass = (*jenv)->FindClass(jenv, "java/io/File");
    if (fileClass == NULL) { dump_exception(jenv, "FindClass File"); job->mainRc = -30; goto finish; }
    jmethodID fileCtor = (*jenv)->GetMethodID(jenv, fileClass, "<init>", "(Ljava/lang/String;)V");
    jmethodID toURI = (*jenv)->GetMethodID(jenv, fileClass, "toURI", "()Ljava/net/URI;");
    jclass uriClass = (*jenv)->FindClass(jenv, "java/net/URI");
    if (uriClass == NULL) { dump_exception(jenv, "FindClass URI"); job->mainRc = -30; goto finish; }
    jmethodID toURL = (*jenv)->GetMethodID(jenv, uriClass, "toURL", "()Ljava/net/URL;");

    jclass urlClass = (*jenv)->FindClass(jenv, "java/net/URL");
    if (urlClass == NULL) { dump_exception(jenv, "FindClass URL"); job->mainRc = -30; goto finish; }
    jobjectArray urls = (*jenv)->NewObjectArray(jenv, job->cpCount, urlClass, NULL);
    for (int i = 0; i < job->cpCount; i++) {
        jstring p = (*jenv)->NewStringUTF(jenv, job->classpath[i]);
        jobject f = (*jenv)->NewObject(jenv, fileClass, fileCtor, p);
        jobject uri = (*jenv)->CallObjectMethod(jenv, f, toURI);
        jobject url = (*jenv)->CallObjectMethod(jenv, uri, toURL);
        (*jenv)->SetObjectArrayElement(jenv, urls, i, url);
        (*jenv)->DeleteLocalRef(jenv, url);
        (*jenv)->DeleteLocalRef(jenv, uri);
        (*jenv)->DeleteLocalRef(jenv, f);
        (*jenv)->DeleteLocalRef(jenv, p);
    }
    LOGI("classpath urls = %d", job->cpCount);

    jclass clClass = (*jenv)->FindClass(jenv, "java/lang/ClassLoader");
    jmethodID getSys = (*jenv)->GetStaticMethodID(jenv, clClass, "getSystemClassLoader",
                                                 "()Ljava/lang/ClassLoader;");
    jobject parent = (*jenv)->CallStaticObjectMethod(jenv, clClass, getSys);
    if (parent == NULL) { dump_exception(jenv, "getSystemClassLoader"); job->mainRc = -31; goto finish; }

    jclass uclClass = (*jenv)->FindClass(jenv, "java/net/URLClassLoader");
    if (uclClass == NULL) { dump_exception(jenv, "FindClass URLClassLoader"); job->mainRc = -32; goto finish; }
    jmethodID uclCtor = (*jenv)->GetMethodID(jenv, uclClass, "<init>",
                                             "([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
    jobject loader = (*jenv)->NewObject(jenv, uclClass, uclCtor, urls, parent);
    if (loader == NULL) { dump_exception(jenv, "new URLClassLoader"); job->mainRc = -33; goto finish; }
    LOGI("URLClassLoader ready");

    jmethodID loadClass = (*jenv)->GetMethodID(jenv, clClass, "loadClass",
                                               "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring jname = (*jenv)->NewStringUTF(jenv, job->mainClass);
    jclass mc = (jclass) (*jenv)->CallObjectMethod(jenv, loader, loadClass, jname);
    (*jenv)->DeleteLocalRef(jenv, jname);
    if (mc == NULL) { dump_exception(jenv, "loadClass main"); job->mainRc = -26; goto finish; }
    LOGI("main class loaded: %s", job->mainClass);

    jmethodID mid = (*jenv)->GetStaticMethodID(jenv, mc, "main", "([Ljava/lang/String;)V");
    if (mid == NULL) { dump_exception(jenv, "GetStaticMethodID main"); job->mainRc = -24; goto finish; }

    jclass strClass = (*jenv)->FindClass(jenv, "java/lang/String");
    jobjectArray jargs = (*jenv)->NewObjectArray(jenv, job->argCount, strClass, NULL);
    for (int i = 0; i < job->argCount; i++) {
        jstring s = (*jenv)->NewStringUTF(jenv, job->mainArgs[i]);
        (*jenv)->SetObjectArrayElement(jenv, jargs, i, s);
        (*jenv)->DeleteLocalRef(jenv, s);
    }

    LOGI("invoking %s.main (%d args)", job->mainClass, job->argCount);
    /* 关键：JVM 不是由 Android Runtime 创建的，classloader.findLibrary 拿不到
     * nativeLibraryDir，System.loadLibrary("lwjgl") 会失败。
     * 这里用绝对路径把所有 LWJGL native 预加载进进程，之后
     * System.loadLibrary 命中已加载的同名库即可。 */
    {
        jclass sysC = (*jenv)->FindClass(jenv, "java/lang/System");
        jmethodID mdLoad = (*jenv)->GetStaticMethodID(jenv, sysC, "load",
                "(Ljava/lang/String;)V");
        const char *natLibs[] = {"liblwjgl.so", "liblwjgl_opengl.so",
                                 "liblwjgl_stb.so", "liblwjgl_tinyfd.so",
                                 "liblwjgl_nanovg.so", "libopenal.so",
                                 "libgl4es_114.so", "libfreetype.so",
                                 "libjnidispatch.so",
                                 // pojavexec 依赖 ANativeWindow_* / EGL / GLES，
                                 // 必须先让这些系统库进全局符号表，否则它的
                                 // 函数指针为 0，Render thread 上跳空指针 SIGSEGV。
                                 "libEGL.so", "libGLESv2.so", "libandroid.so",
                                 "liblinkerhook.so", "libOSMesa.so"};
        /* 关键：libpojavexec.so 不能在这里 System.load()！
         * 它的 JNI_OnLoad 里第一句是
         *   if (pojav_environ->dalvikJavaVMPtr == NULL) { ... 走 Dalvik 分支 ... }
         * 由 System.load() 触发时拿到的 vm 是 ART 的，于是它记下 ART VM 并去
         * FindClass("org/lwjgl/glfw/CallbackBridge")，那个类在我们的 JVM 里，
         * ART 找不到 -> bridgeClazz/bridgeClazz 相关 jmethodID 全 NULL。
         * 之后我们的 JVM 里 GLFW 再 loadLibrary 时 JNI_OnLoad 走 else-if 分支，
         * 而 pojav_environ->keyDownBuffer 等依然为 NULL，hookExec() 里
         * RegisterNatives(NULL, ...) 直接跳地址 0。
         * 因此 pojavexec 必须由 JVM 侧 System.loadLibrary("pojavexec") 触发。
         */
        char full[1024];
        for (int i = 0; i < 14; i++) {
            snprintf(full, sizeof(full), "%s/%s", job->nativeLibDir, natLibs[i]);
            jstring pj = (*jenv)->NewStringUTF(jenv, full);
            (*jenv)->CallStaticVoidMethod(jenv, sysC, mdLoad, pj);
            if ((*jenv)->ExceptionCheck(jenv)) {
                (*jenv)->ExceptionClear(jenv);
                LOGI("preload skipped: %s", natLibs[i]);
            } else {
                LOGI("preload ok: %s", natLibs[i]);
            }
            (*jenv)->DeleteLocalRef(jenv, pj);
        }
    }

    /* 打印关键系统属性，Fabric 靠 java.class.path 定位游戏 jar */
    jclass sysClass = (*jenv)->FindClass(jenv, "java/lang/System");
    jmethodID getProp = (*jenv)->GetStaticMethodID(jenv, sysClass, "getProperty",
            "(Ljava/lang/String;)Ljava/lang/String;");
    const char *probe[] = {"java.class.path", "java.home", "sun.java.command",
                           "user.dir", "os.arch", "java.version"};
    for (int i = 0; i < 6; i++) {
        jstring key = (*jenv)->NewStringUTF(jenv, probe[i]);
        jstring val = (jstring) (*jenv)->CallStaticObjectMethod(jenv, sysClass, getProp, key);
        const char *vc = val ? (*jenv)->GetStringUTFChars(jenv, val, NULL) : NULL;
        LOGI("PROP %s = %s", probe[i], vc ? vc : "(null)");
        if (vc) (*jenv)->ReleaseStringUTFChars(jenv, val, vc);
        (*jenv)->DeleteLocalRef(jenv, key);
    }

    (*jenv)->CallStaticVoidMethod(jenv, mc, mid, jargs);
    if ((*jenv)->ExceptionCheck(jenv)) {
        dump_exception(jenv, "main");
        job->mainRc = -25;
    } else {
        job->mainRc = 0;
        LOGI("main returned normally");
    }

finish:
    pthread_mutex_lock(&job->lock);
    job->done = 1;
    pthread_cond_signal(&job->cond);
    pthread_mutex_unlock(&job->lock);
    return NULL;
}

JNIEXPORT jintArray JNICALL
Java_com_mcbox_launcher_runtime_JvmBridge_nativeLaunch(JNIEnv *env, jclass clazz,
        jstring libjvmPath, jobjectArray jvmOptions,
        jstring mainClassName, jobjectArray mainArgs, jobjectArray classpath,
        jstring nativeLibDir) {
    LaunchJob job;
    memset(&job, 0, sizeof(job));
    job.libjvmPath = dup_utf(env, libjvmPath);
    job.options = dup_array(env, jvmOptions, &job.optionCount);
    job.mainClass = dup_utf(env, mainClassName);
    job.nativeLibDir = dup_utf(env, nativeLibDir);
    job.mainArgs = dup_array(env, mainArgs, &job.argCount);
    job.classpath = dup_array(env, classpath, &job.cpCount);
    job.vmRc = -1;
    job.mainRc = -1;
    pthread_mutex_init(&job.lock, NULL);
    pthread_cond_init(&job.cond, NULL);

    /* 异常详情落盘路径由 Kotlin 侧通过环境变量给出 */
    const char *dumpPath = getenv("MCBOX_EX_DUMP");
    if (dumpPath != NULL) {
        snprintf(g_dumpPath, sizeof(g_dumpPath), "%s", dumpPath);
        LOGI("exception dump -> %s", g_dumpPath);
    }

    pthread_t tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    /* JVM 需要较大栈，默认 pthread 栈不够 */
    pthread_attr_setstacksize(&attr, 8 * 1024 * 1024);
    int rc = pthread_create(&tid, &attr, launch_thread, &job);
    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        return NULL;
    }

    pthread_mutex_lock(&job.lock);
    while (!job.done) {
        pthread_cond_wait(&job.cond, &job.lock);
    }
    pthread_mutex_unlock(&job.lock);

    jintArray result = (*env)->NewIntArray(env, 2);
    jint vals[2];
    vals[0] = job.vmRc;
    vals[1] = job.mainRc;
    (*env)->SetIntArrayRegion(env, result, 0, 2, vals);
    return result;
}