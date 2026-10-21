// Boots the extension host jar through the app's own JNI shim (OneechanJVM/oneechan_jvm.c) on the
// JVM linked into this executable, with a VM option file as the app bundles it, and makes one
// host.hello call (jvm-ios/smoke-openj9-ios.sh host).
//   host_smoke JAVA_HOME HOST_JAR OPTIONS_FILE WORK_DIR
// Exit status: 0 ok, 1 the host failed to start or answer.
#include "oneechan_jvm.h"

#include <mach/mach.h>

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t answered = PTHREAD_COND_INITIALIZER;
static char *reply;

static void complete(void *context, int64_t id, const uint8_t *bytes, size_t length) {
    (void)context;
    pthread_mutex_lock(&lock);
    reply = strndup((const char *)bytes, length);
    printf("reply %lld: %s\n", (long long)id, reply);
    pthread_cond_broadcast(&answered);
    pthread_mutex_unlock(&lock);
}

static void emit(void *context, const uint8_t *bytes, size_t length) {
    (void)context;
    printf("event: %.*s\n", (int)(length > 200 ? 200 : length), (const char *)bytes);
}

/* Resident size and physical footprint (what iOS's memory limit counts), in MB. */
static void print_memory(const char *when) {
    task_vm_info_data_t info;
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&info, &count) == KERN_SUCCESS) {
        printf("memory %s: resident %.1f MB, footprint %.1f MB\n", when, info.resident_size / 1048576.0, info.phys_footprint / 1048576.0);
    }
}

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
    if (argc != 5) { fprintf(stderr, "usage: host_smoke JAVA_HOME HOST_JAR OPTIONS_FILE WORK_DIR\n"); return 64; }
    const char *home = argv[1], *jar = argv[2], *work = argv[4];
    char data[1024], cache[1024], tmp[1024];
    snprintf(data, sizeof data, "%s/data", work);
    snprintf(cache, sizeof cache, "%s/cache", work);
    snprintf(tmp, sizeof tmp, "%s/tmp", work);
    mkdir(work, 0755); mkdir(data, 0755); mkdir(cache, 0755); mkdir(tmp, 0755);

    // The options JNITransport builds: common ones, then the VM's file with ${...} filled in.
    char *options[128];
    int count = 0;
    char buffer[2048];
    snprintf(buffer, sizeof buffer, "-Djava.class.path=%s", jar); options[count++] = strdup(buffer);
    snprintf(buffer, sizeof buffer, "-Djava.io.tmpdir=%s", tmp); options[count++] = strdup(buffer);
    snprintf(buffer, sizeof buffer, "-Duser.home=%s", data); options[count++] = strdup(buffer);
    options[count++] = strdup("-Xmx512m");
    options[count++] = strdup("-Djava.awt.headless=true");
    options[count++] = strdup("-Dfile.encoding=UTF-8");
    snprintf(buffer, sizeof buffer, "-Djava.home=%s", home); options[count++] = strdup(buffer);
    FILE *file = fopen(argv[3], "r");
    if (!file) { perror(argv[3]); return 1; }
    char line[1024];
    while (fgets(line, sizeof line, file) && count < 120) {
        char *start = line, *end;
        while (*start == ' ' || *start == '\t') start++;
        end = start + strlen(start);
        while (end > start && (end[-1] == '\n' || end[-1] == '\r' || end[-1] == ' ')) *--end = 0;
        if (!*start || *start == '#') continue;
        char expanded[2048] = "";
        for (const char *p = start; *p;) {
            if (!strncmp(p, "${cacheDir}", 11)) { strcat(expanded, cache); p += 11; }
            else if (!strncmp(p, "${dataDir}", 10)) { strcat(expanded, data); p += 10; }
            else if (!strncmp(p, "${tmpDir}", 9)) { strcat(expanded, tmp); p += 9; }
            else if (!strncmp(p, "${javaHome}", 11)) { strcat(expanded, home); p += 11; }
            else { strncat(expanded, p, 1); p++; }
        }
        options[count++] = strdup(expanded);
    }
    fclose(file);
    for (int i = 0; i < count; i++) printf("option: %s\n", options[i]);

    char config[4096];
    snprintf(config, sizeof config, "{\"dataDir\":\"%s\",\"cacheDir\":\"%s\",\"tmpDir\":\"%s\"}", data, cache, tmp);
    char error[1024] = "";
    oc_jvm_callbacks callbacks = {.context = NULL, .complete = complete, .emit = emit};
    double started = now();
    if (oc_jvm_start(NULL, (const char *const *)options, count, "oneechan/host/embedded/EmbeddedBridge",
                     (const uint8_t *)config, strlen(config), callbacks, error, sizeof error) != 0) {
        fprintf(stderr, "FAIL: %s\n", error);
        return 1;
    }
    double booted = now();
    printf("host ready in %.3f s\n", booted - started);
    print_memory("after start");

    const char *hello = "{\"id\":\"a\",\"method\":\"host.hello\",\"params\":{}}";
    oc_jvm_call(1, (const uint8_t *)hello, strlen(hello));
    pthread_mutex_lock(&lock);
    while (!reply) pthread_cond_wait(&answered, &lock);
    pthread_mutex_unlock(&lock);
    printf("hello answered in %.3f s\n", now() - booted);
    print_memory("after hello");
    if (!strstr(reply, getenv("EXPECT_JVM") ? getenv("EXPECT_JVM") : "OpenJ9")) { fprintf(stderr, "FAIL: hello does not name the expected JVM\n"); return 1; }
    printf("HOST SMOKE OK\n");
    fflush(stdout);
    _exit(0);
}
