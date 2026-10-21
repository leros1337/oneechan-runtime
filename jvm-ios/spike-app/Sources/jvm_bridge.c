#include "jvm_bridge.h"
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

typedef struct { char bundle[1024]; char tmp[1024]; char args[1024]; } spike_args;

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

static void *jvm_main(void *p) {
    spike_args *a = p;
    // launch args: "--host\n<port>\n<data-name>" selects the extension host
    char *argv[8] = {0};
    int argc = 0;
    for (char *s = a->args, *tok; argc < 8 && (tok = strsep(&s, "\n")) != NULL;) if (*tok) argv[argc++] = tok;
    int host = argc >= 3 && strcmp(argv[0], "--host") == 0;

    char o_home[1200], o_cp[2400], o_tmp[1200], o_uhome[1200], data[1200];
    snprintf(o_home, sizeof o_home, "-Djava.home=%s/jre", a->bundle);
    if (host) snprintf(o_cp, sizeof o_cp, "-Djava.class.path=%s/extra/host.jar", a->bundle);
    else snprintf(o_cp, sizeof o_cp, "-Djava.class.path=%s/spike.jar", a->bundle);
    snprintf(o_tmp, sizeof o_tmp, "-Djava.io.tmpdir=%s", a->tmp);
    snprintf(o_uhome, sizeof o_uhome, "-Duser.home=%s", a->tmp);
    if (host) snprintf(data, sizeof data, "%s/%s", a->tmp, argv[2]);
    JavaVMOption options[] = {
        {.optionString = o_home},
        {.optionString = o_cp},
        {.optionString = o_tmp},
        {.optionString = o_uhome},
        {.optionString = host ? "-Xmx512m" : "-Xmx256m"},
        {.optionString = "-XX:+UseSerialGC"},
        {.optionString = "-XX:-UsePerfData"},     // no hsperfdata mmap file in /tmp
        {.optionString = "-Xshare:off"},          // no CDS archive in a cross-built image
        {.optionString = "-Djava.awt.headless=true"},
        {.optionString = "-XX:+UnlockDiagnosticVMOptions"},
        {.optionString = "-XX:-BytecodeVerificationRemote"},  // what the extension host needs
    };
    JavaVMInitArgs vm_args = {
        .version = JNI_VERSION_21,
        .nOptions = (jint)(sizeof options / sizeof options[0]),
        .options = options,
        .ignoreUnrecognized = JNI_FALSE,
    };
    for (int i = 0; i < vm_args.nOptions; i++) fprintf(stderr, "[native] option %s\n", options[i].optionString);

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    double t0 = now_ms();
    jint rc = JNI_CreateJavaVM(&vm, (void **)&env, &vm_args);
    fprintf(stderr, "[native] JNI_CreateJavaVM rc=%d in %.0f ms\n", rc, now_ms() - t0);
    if (rc != JNI_OK) { free(a); return NULL; }

    const char *main_class = host ? "oneechan/host/desktop/MainKt" : "Spike";
    double t1 = now_ms();
    jclass cls = (*env)->FindClass(env, main_class);
    jmethodID mid = cls ? (*env)->GetStaticMethodID(env, cls, "main", "([Ljava/lang/String;)V") : NULL;
    if (mid) {
        jclass str = (*env)->FindClass(env, "java/lang/String");
        jobjectArray jargs = (*env)->NewObjectArray(env, host ? 4 : 0, str, NULL);
        if (host) {
            const char *hargs[4] = {"--data", data, "--port", argv[1]};
            for (int i = 0; i < 4; i++) (*env)->SetObjectArrayElement(env, jargs, i, (*env)->NewStringUTF(env, hargs[i]));
        }
        (*env)->CallStaticVoidMethod(env, cls, mid, jargs);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        fprintf(stderr, "[native] %s.main threw\n", main_class);
    } else {
        fprintf(stderr, "[native] %s.main returned in %.0f ms (JVM total %.0f ms)\n", main_class, now_ms() - t1, now_ms() - t0);
    }
    fflush(stdout);
    (*vm)->DetachCurrentThread(vm);  // the host keeps running on its own (non-daemon) threads
    free(a);
    return NULL;
}

void spike_start_jvm(const char *bundlePath, const char *tmpDir, const char *args) {
    if (args && strcmp(args, "--nojvm") == 0) return;  // baseline for memory measurements
    spike_args *a = calloc(1, sizeof *a);
    strlcpy(a->bundle, bundlePath, sizeof a->bundle);
    strlcpy(a->tmp, tmpDir, sizeof a->tmp);
    strlcpy(a->args, args ? args : "", sizeof a->args);
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 8u << 20);  // 8 MB: Zero's C++ interpreter recurses on the native stack
    pthread_t t;
    if (pthread_create(&t, &attr, jvm_main, a) != 0) { fprintf(stderr, "[native] pthread_create failed\n"); free(a); }
    else pthread_detach(t);
    pthread_attr_destroy(&attr);
}
