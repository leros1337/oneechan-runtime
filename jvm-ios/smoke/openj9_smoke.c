// Smoke test for the statically linked OpenJ9 (jvm-ios/smoke-openj9-ios.sh): boots the VM linked
// into this executable, runs Smoke.run() and checks that the VM mapped no executable memory.
//   openj9_smoke JAVA_HOME CLASSPATH [extra JVM options...]
// Exit status: 0 ok, 1 JVM not linked, 2 JNI_CreateJavaVM failed, 3 Java exception,
// 4 new executable memory outside loaded images.
#include <dlfcn.h>
#include <jni.h>
#include <mach-o/dyld.h>
#include <mach/mach.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef jint (JNICALL *create_vm_fn)(JavaVM **, void **, void *);

enum { MAX_REGIONS = 4096 };
struct region { vm_address_t start; vm_size_t size; };

static int argc_;
static char **argv_;

// The executable regions of this task.
static int exec_regions(struct region *out) {
    int n = 0;
    vm_address_t address = 0;
    for (;;) {
        vm_size_t size = 0;
        vm_region_basic_info_data_64_t info;
        mach_msg_type_number_t count = VM_REGION_BASIC_INFO_COUNT_64;
        mach_port_t object = MACH_PORT_NULL;
        if (vm_region_64(mach_task_self(), &address, &size, VM_REGION_BASIC_INFO_64, (vm_region_info_t)&info, &count, &object) != KERN_SUCCESS) break;
        if ((info.protection & VM_PROT_EXECUTE) && n < MAX_REGIONS) out[n++] = (struct region){address, size};
        address += size;
    }
    return n;
}

static int fail(JNIEnv *env, const char *what) {
    fprintf(stderr, "FAIL: exception in %s\n", what);
    (*env)->ExceptionDescribe(env);
    exit(3);
}

static void *run(void *unused) {
    (void)unused;
    static struct region before[MAX_REGIONS], after[MAX_REGIONS];
    int before_count = exec_regions(before);

    create_vm_fn create = (create_vm_fn)dlsym(RTLD_DEFAULT, "JNI_CreateJavaVM");
    if (!create) { fprintf(stderr, "FAIL: JNI_CreateJavaVM is not linked\n"); exit(1); }
    char home[2048], classpath[2048];
    snprintf(home, sizeof home, "-Djava.home=%s", argv_[1]);
    snprintf(classpath, sizeof classpath, "-Djava.class.path=%s", argv_[2]);
    const char *fixed[] = {home, classpath, "-Xint", "-Xnojit", "-Xnoaot", "-Xverify:none", "-Xshareclasses:none",
                           "-Dcom.ibm.tools.attach.enable=no", "-Xdump:none", "-Xmx128m"};
    int fixed_count = sizeof fixed / sizeof fixed[0], extra = argc_ - 3;
    JavaVMOption *options = calloc((size_t)(fixed_count + extra), sizeof *options);
    for (int i = 0; i < fixed_count; i++) options[i].optionString = (char *)fixed[i];
    for (int i = 0; i < extra; i++) options[fixed_count + i].optionString = argv_[3 + i];
    JavaVMInitArgs args = {.version = JNI_VERSION_21, .nOptions = fixed_count + extra, .options = options, .ignoreUnrecognized = JNI_FALSE};
    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jint rc = create(&vm, (void **)&env, &args);
    if (rc != JNI_OK) { fprintf(stderr, "FAIL: JNI_CreateJavaVM returned %d\n", (int)rc); exit(2); }

    jclass system = (*env)->FindClass(env, "java/lang/System");
    jmethodID property = (*env)->GetStaticMethodID(env, system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    const char *names[] = {"java.vm.name", "java.vm.info", "java.home"};
    for (int i = 0; i < 3; i++) {
        jstring value = (*env)->CallStaticObjectMethod(env, system, property, (*env)->NewStringUTF(env, names[i]));
        if ((*env)->ExceptionCheck(env)) fail(env, names[i]);
        const char *utf = value ? (*env)->GetStringUTFChars(env, value, NULL) : "(null)";
        printf("%s = %s\n", names[i], utf);
    }
    jclass smoke = (*env)->FindClass(env, "Smoke");
    if (!smoke || (*env)->ExceptionCheck(env)) fail(env, "FindClass(Smoke)");
    jmethodID go = (*env)->GetStaticMethodID(env, smoke, "run", "()Ljava/lang/String;");
    jstring result = (*env)->CallStaticObjectMethod(env, smoke, go);
    if ((*env)->ExceptionCheck(env)) fail(env, "Smoke.run");
    printf("smoke: %s\n", (*env)->GetStringUTFChars(env, result, NULL));

    // Executable memory: any region that is new since before JNI_CreateJavaVM must belong to a
    // loaded image (a framework dyld mapped); anonymous executable memory is what iOS forbids.
    int after_count = exec_regions(after), anonymous = 0;
    for (int i = 0; i < after_count; i++) {
        int seen = 0;
        for (int j = 0; j < before_count && !seen; j++) seen = before[j].start == after[i].start;
        if (seen) continue;
        Dl_info info;
        if (dladdr((void *)after[i].start, &info) && info.dli_fname) {
            printf("new executable region %#lx+%#lx in %s\n", (unsigned long)after[i].start, (unsigned long)after[i].size, info.dli_fname);
        } else {
            printf("ANONYMOUS executable region %#lx+%#lx\n", (unsigned long)after[i].start, (unsigned long)after[i].size);
            anonymous++;
        }
    }
    printf("executable regions: %d before, %d after, %d anonymous\n", before_count, after_count, anonymous);
    if (anonymous) { fprintf(stderr, "FAIL: the VM mapped executable memory\n"); exit(4); }
    printf("SMOKE OK\n");
    fflush(stdout);
    exit(0);
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: openj9_smoke JAVA_HOME CLASSPATH [JVM options]\n"); return 64; }
    argc_ = argc;
    argv_ = argv;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 8u << 20);
    pthread_t thread;
    pthread_create(&thread, &attr, run, NULL);
    pthread_join(thread, NULL);
    return 0;
}
