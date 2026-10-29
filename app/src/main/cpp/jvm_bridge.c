#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "JvmBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef jint (*CreateJavaVM_t)(JavaVM **, void **, void *);

static JavaVM *g_vm = NULL;

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
    LOGI("JNI_CreateJavaVM rc=%d", rc);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_mcbox_launcher_runtime_JvmBridge_nativeCallMain(JNIEnv *env, jclass clazz,
        jstring mainClassName, jobjectArray mainArgs) {
    if (g_vm == NULL) return -20;

    JNIEnv *jenv = NULL;
    jint rc = (*g_vm)->GetEnv(g_vm, (void **) &jenv, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &jenv, NULL) != JNI_OK) return -21;
    } else if (rc != JNI_OK) {
        return -22;
    }

    const char *name = (*env)->GetStringUTFChars(env, mainClassName, NULL);
    char dotted[512];
    snprintf(dotted, sizeof(dotted), "%s", name);
    for (char *p = dotted; *p; p++) if (*p == '/') *p = '.';

    jclass mc = (*jenv)->FindClass(jenv, dotted);
    if (mc == NULL) {
        LOGE("FindClass failed: %s", dotted);
        (*env)->ExceptionDescribe(env);
        return -23;
    }
    jmethodID mid = (*jenv)->GetStaticMethodID(jenv, mc, "main", "([Ljava/lang/String;)V");
    if (mid == NULL) {
        LOGE("GetStaticMethodID(main) failed");
        (*env)->ExceptionDescribe(env);
        return -24;
    }
    (*jenv)->CallStaticVoidMethod(jenv, mc, mid, mainArgs);
    if ((*jenv)->ExceptionCheck(jenv)) {
        (*jenv)->ExceptionDescribe(jenv);
        return -25;
    }
    return 0;
}
