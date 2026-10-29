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
            LOGE("[%s] exception: %s", where, c ? c : "?");
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

JNIEXPORT jint JNICALL
Java_com_mcbox_launcher_runtime_JvmBridge_nativeCallMain(JNIEnv *env, jclass clazz,
        jstring mainClassName, jobjectArray mainArgs) {
    if (g_vm == NULL) return -20;
    JNIEnv *e = get_env();
    if (e == NULL) return -21;

    const char *name = (*env)->GetStringUTFChars(env, mainClassName, NULL);
    if (name == NULL) return -22;
    char dotted[1024];
    snprintf(dotted, sizeof(dotted), "%s", name);
    for (char *p = dotted; *p; p++) if (*p == '/') *p = '.';
    (*env)->ReleaseStringUTFChars(env, mainClassName, name);
    LOGI("main class = %s", dotted);

    jclass mc = (*e)->FindClass(e, dotted);
    if (mc == NULL) {
        dump_exception(e, "FindClass");
        LOGE("FindClass failed, retry via system ClassLoader");
        jclass clClass = (*e)->FindClass(e, "java/lang/ClassLoader");
        if (clClass == NULL) { dump_exception(e, "FindClass ClassLoader"); return -23; }
        jmethodID getSys = (*e)->GetStaticMethodID(e, clClass, "getSystemClassLoader",
                                                   "()Ljava/lang/ClassLoader;");
        if (getSys == NULL) { dump_exception(e, "getSystemClassLoader id"); return -23; }
        jobject cl = (*e)->CallStaticObjectMethod(e, clClass, getSys);
        if (cl == NULL) { dump_exception(e, "getSystemClassLoader call"); return -23; }
        jmethodID loadClass = (*e)->GetMethodID(e, clClass, "loadClass",
                                                "(Ljava/lang/String;)Ljava/lang/Class;");
        if (loadClass == NULL) { dump_exception(e, "loadClass id"); return -23; }
        jstring jname = (*e)->NewStringUTF(e, dotted);
        jclass found = (jclass) (*e)->CallObjectMethod(e, cl, loadClass, jname);
        (*e)->DeleteLocalRef(e, jname);
        if (found == NULL) {
            dump_exception(e, "ClassLoader.loadClass");
            return -26;
        }
        mc = found;
    }

    jmethodID mid = (*e)->GetStaticMethodID(e, mc, "main", "([Ljava/lang/String;)V");
    if (mid == NULL) {
        dump_exception(e, "GetStaticMethodID(main)");
        return -24;
    }

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