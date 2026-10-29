#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>

#define TAG "JvmBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef jint (*CreateJavaVM_t)(JavaVM **, void **, void *);

static JavaVM *g_vm = NULL;

/* 由 Kotlin 侧传入、在专用线程里执行的全部参数 */
typedef struct {
    char *libjvmPath;
    int optionCount;
    char **options;
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
            LOGE("[%s] EX %s", where, c ? c : "?");
            (*e)->ReleaseStringUTFChars(e, n, c);
        }
    }
    if (tm) {
        jstring m = (jstring) (*e)->CallObjectMethod(e, t, tm);
        if (m) {
            const char *c = (*e)->GetStringUTFChars(e, m, NULL);
            LOGE("[%s] MSG %s", where, c ? c : "?");
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
        LOGE("[%s] cause[%d] %s: %s", where, depth, cnc ? cnc : "?", cmc ? cmc : "?");
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
static void *launch_thread(void *arg) {
    LaunchJob *job = (LaunchJob *) arg;

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
        jstring mainClassName, jobjectArray mainArgs, jobjectArray classpath) {
    LaunchJob job;
    memset(&job, 0, sizeof(job));
    job.libjvmPath = dup_utf(env, libjvmPath);
    job.options = dup_array(env, jvmOptions, &job.optionCount);
    job.mainClass = dup_utf(env, mainClassName);
    job.mainArgs = dup_array(env, mainArgs, &job.argCount);
    job.classpath = dup_array(env, classpath, &job.cpCount);
    job.vmRc = -1;
    job.mainRc = -1;
    pthread_mutex_init(&job.lock, NULL);
    pthread_cond_init(&job.cond, NULL);

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