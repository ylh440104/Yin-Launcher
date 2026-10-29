#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "JvmBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef jint (*CreateJavaVM_t)(JavaVM **, void **, void *);

static JavaVM *g_vm = NULL;

/* 拿到属于 JVM 的 JNIEnv。调用线程可能是 ART 线程，必须先 Attach。 */
static JNIEnv *get_env(void) {
    if (g_vm == NULL) return NULL;
    JNIEnv *e = NULL;
    jint rc = (*g_vm)->GetEnv(g_vm, (void **) &e, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        JavaVMAttachArgs a;
        a.version = JNI_VERSION_1_6;
        a.name = "mcbox-main";
        a.group = NULL;
        if ((*g_vm)->AttachCurrentThread(g_vm, &e, &a) != JNI_OK) return NULL;
    } else if (rc != JNI_OK) {
        return NULL;
    }
    return e;
}

static void dump_exception(JNIEnv *e, const char *where) {
    if (!(*e)->ExceptionCheck(e)) return;
    jthrowable t = (*e)->ExceptionOccurred(e);
    (*e)->ExceptionClear(e);
    jclass tc = (*e)->GetObjectClass(e, t);
    jmethodID ts = (*e)->GetMethodID(e, tc, "toString", "()Ljava/lang/String;");
    if (ts) {
        jstring s = (jstring) (*e)->CallObjectMethod(e, t, ts);
        if (s) {
            const char *c = (*e)->GetStringUTFChars(e, s, NULL);
            LOGE("[%s] %s", where, c ? c : "?");
            (*e)->ReleaseStringUTFChars(e, s, c);
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_mcbox_launcher_runtime_JvmBridge_nativeCreateVm(JNIEnv *env, jclass clazz,
        jstring libjvmPath, jobjectArray jvmOptions) {
    const char *path = (*env)->GetStringUTFChars(env, libjvmPath, NULL);
    void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        LOGE("dlopen failed: %s : %s", path, dlerror());
        (*env)->ReleaseStringUTFChars(env, libjvmPath, path);
        return -10;
    }
    CreateJavaVM_t create = (CreateJavaVM_t) dlsym(handle, "JNI_CreateJavaVM");
    if (create == NULL) {
        LOGE("dlsym JNI_CreateJavaVM failed: %s", dlerror());
        (*env)->ReleaseStringUTFChars(env, libjvmPath, path);
        return -11;
    }
    (*env)->ReleaseStringUTFChars(env, libjvmPath, path);

    int n = (*env)->GetArrayLength(env, jvmOptions);
    JavaVMOption *opts = (JavaVMOption *) calloc(n > 0 ? n : 1, sizeof(JavaVMOption));
    for (int i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, jvmOptions, i);
        opts[i].optionString = (char *) (*env)->GetStringUTFChars(env, s, NULL);
        opts[i].extraInfo = NULL;
    }

    JavaVMInitArgs args;
    args.version = JNI_VERSION_1_6;
    args.nOptions = n;
    args.options = opts;
    args.ignoreUnrecognized = JNI_FALSE;

    JNIEnv *jenv = NULL;
    jint rc = create(&g_vm, (void **) &jenv, &args);
    LOGI("JNI_CreateJavaVM rc=%d vm=%p", rc, (void *) g_vm);
    return rc;
}

/*
 * 用 URLClassLoader 显式加载 classpath。
 *
 * 纯 JNI_CreateJavaVM 不会像 java launcher 那样依据 -Djava.class.path
 * 构造应用类加载器，所以 -cp 对这些 jar 无效，必须自己建 URLClassLoader。
 */
JNIEXPORT jint JNICALL
Java_com_mcbox_launcher_runtime_JvmBridge_nativeCallMain(JNIEnv *env, jclass clazz,
        jstring mainClassName, jobjectArray mainArgs, jobjectArray classpath) {
    if (g_vm == NULL) return -20;
    JNIEnv *e = get_env();
    if (e == NULL) return -21;

    /* main 类名：/ -> . */
    const char *name = (*env)->GetStringUTFChars(env, mainClassName, NULL);
    if (name == NULL) return -22;
    char dotted[1024];
    snprintf(dotted, sizeof(dotted), "%s", name);
    for (char *p = dotted; *p; p++) if (*p == '/') *p = '.';
    (*env)->ReleaseStringUTFChars(env, mainClassName, name);
    LOGI("main class = %s", dotted);

    /* 建 URL[] */
    jclass fileClass = (*e)->FindClass(e, "java/io/File");
    if (fileClass == NULL) { dump_exception(e, "FindClass File"); return -30; }
    jmethodID fileCtor = (*e)->GetMethodID(e, fileClass, "<init>", "(Ljava/lang/String;)V");
    jmethodID toURI = (*e)->GetMethodID(e, fileClass, "toURI", "()Ljava/net/URI;");
    jclass uriClass = (*e)->FindClass(e, "java/net/URI");
    jmethodID toURL = (*e)->GetMethodID(e, uriClass, "toURL", "()Ljava/net/URL;");

    int cpCount = (*env)->GetArrayLength(env, classpath);
    jclass urlClass = (*e)->FindClass(e, "java/net/URL");
    jobjectArray urls = (*e)->NewObjectArray(e, cpCount, urlClass, NULL);
    for (int i = 0; i < cpCount; i++) {
        jstring p = (jstring) (*env)->GetObjectArrayElement(env, classpath, i);
        jobject f = (*e)->NewObject(e, fileClass, fileCtor, p);
        jobject uri = (*e)->CallObjectMethod(e, f, toURI);
        jobject url = (*e)->CallObjectMethod(e, uri, toURL);
        (*e)->SetObjectArrayElement(e, urls, i, url);
        (*e)->DeleteLocalRef(e, url);
        (*e)->DeleteLocalRef(e, uri);
        (*e)->DeleteLocalRef(e, f);
        (*e)->DeleteLocalRef(e, p);
    }
    LOGI("classpath urls = %d", cpCount);

    /* 父加载器用当前线程的系统加载器，保证 JDK 类可见 */
    jclass clClass = (*e)->FindClass(e, "java/lang/ClassLoader");
    jmethodID getSys = (*e)->GetStaticMethodID(e, clClass, "getSystemClassLoader",
                                               "()Ljava/lang/ClassLoader;");
    jobject parent = (*e)->CallStaticObjectMethod(e, clClass, getSys);
    if (parent == NULL) { dump_exception(e, "getSystemClassLoader"); return -31; }

    jclass uclClass = (*e)->FindClass(e, "java/net/URLClassLoader");
    if (uclClass == NULL) { dump_exception(e, "FindClass URLClassLoader"); return -32; }
    jmethodID uclCtor = (*e)->GetMethodID(e, uclClass, "<init>",
                                          "([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
    jobject loader = (*e)->NewObject(e, uclClass, uclCtor, urls, parent);
    if (loader == NULL) { dump_exception(e, "new URLClassLoader"); return -33; }
    LOGI("URLClassLoader created");

    /* loader.loadClass(mainClass) */
    jclass ldrClass = (*e)->FindClass(e, "java/lang/ClassLoader");
    jmethodID loadClass = (*e)->GetMethodID(e, ldrClass, "loadClass",
                                            "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring jname = (*e)->NewStringUTF(e, dotted);
    jclass mc = (jclass) (*e)->CallObjectMethod(e, loader, loadClass, jname);
    (*e)->DeleteLocalRef(e, jname);
    if (mc == NULL) { dump_exception(e, "URLClassLoader.loadClass"); return -26; }
    LOGI("main class loaded");

    jmethodID mid = (*e)->GetStaticMethodID(e, mc, "main", "([Ljava/lang/String;)V");
    if (mid == NULL) { dump_exception(e, "GetStaticMethodID(main)"); return -24; }

    /* 参数数组 */
    int argc = (*env)->GetArrayLength(env, mainArgs);
    jclass strClass = (*e)->FindClass(e, "java/lang/String");
    jobjectArray jargs = (*e)->NewObjectArray(e, argc, strClass, NULL);
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, mainArgs, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        jstring copy = (*e)->NewStringUTF(e, c ? c : "");
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*e)->SetObjectArrayElement(e, jargs, i, copy);
        (*e)->DeleteLocalRef(e, copy);
    }

    LOGI("invoking %s.main with %d args", dotted, argc);
    (*e)->CallStaticVoidMethod(e, mc, mid, jargs);
    if ((*e)->ExceptionCheck(e)) {
        dump_exception(e, "main");
        return -25;
    }
    LOGI("main returned normally");
    return 0;
}