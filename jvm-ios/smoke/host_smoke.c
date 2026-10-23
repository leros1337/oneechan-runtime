// Boots the extension host jar through the app's own JNI shim (OneechanJVM/oneechan_jvm.c) on the
// JVM linked into this executable, with a VM option file as the app bundles it, makes a host.hello
// call, then fetches two bodies through the shim's binary channel for extension HTTP: a GET answered
// with every byte value, and a POST whose body is echoed back (`debug.netFetch`, which writes each
// body to a file) (jvm-ios/smoke-openj9-ios.sh host).
//   host_smoke JAVA_HOME HOST_JAR OPTIONS_FILE WORK_DIR
// Exit status: 0 ok, 1 the host failed to start or answer, or a body changed.
#include "oneechan_jvm.h"

#include <mach/mach.h>

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define GET_SIZE 65536
#define POST_SIZE 100000

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t answered = PTHREAD_COND_INITIALIZER;
static char *replies[8];
static int bad_request_body;

static void complete(void *context, int64_t id, const uint8_t *bytes, size_t length) {
    (void)context;
    pthread_mutex_lock(&lock);
    if (id >= 0 && id < 8) replies[id] = strndup((const char *)bytes, length);
    printf("reply %lld: %.*s\n", (long long)id, (int)(length > 600 ? 600 : length), (const char *)bytes);
    pthread_cond_broadcast(&answered);
    pthread_mutex_unlock(&lock);
}

static char *wait_reply(int64_t id) {
    pthread_mutex_lock(&lock);
    while (!replies[id]) pthread_cond_wait(&answered, &lock);
    pthread_mutex_unlock(&lock);
    return replies[id];
}

/* The app's side of extension HTTP: a POST gets its body back (which must be bytes i & 0xFF), anything else
   GET_SIZE bytes i & 0xFF. The request body is ours to free. */
static void fetch(void *context, int64_t id, const uint8_t *meta, size_t meta_length, uint8_t *body, size_t body_length) {
    (void)context;
    printf("fetch %lld: %.*s (body %s, %zu bytes)\n", (long long)id, (int)meta_length, (const char *)meta, body ? "raw" : "none", body_length);
    int post = memmem(meta, meta_length, "\"method\":\"POST\"", 15) != NULL;
    uint8_t *answer = body;
    size_t answer_length = body_length;
    if (post) {
        for (size_t i = 0; i < body_length; i++) if (body[i] != (uint8_t)i) { bad_request_body = 1; break; }
        if (body_length != POST_SIZE) bad_request_body = 1;
    } else {
        if (body) bad_request_body = 1;
        free(body);
        answer = malloc(GET_SIZE);
        answer_length = GET_SIZE;
        for (size_t i = 0; i < GET_SIZE; i++) answer[i] = (uint8_t)i;
    }
    char result[256];
    int length = snprintf(result, sizeof result, "{\"id\":\"%lld\",\"code\":200,\"message\":\"OK\",\"headers\":[[\"Content-Type\",\"application/octet-stream\"]]}", (long long)id);
    if (oc_jvm_fetch_result(id, (const uint8_t *)result, (size_t)length, answer, answer_length) != 0) printf("fetch %lld: not queued\n", (long long)id);
}

/* Whether the file at the n-th "path" of a debug.netFetch reply holds `size` bytes i & 0xFF; deletes it. */
static int body_intact(const char *reply, int n, size_t size) {
    const char *at = reply;
    for (int i = 0; i <= n; i++) {
        at = strstr(at, "\"path\":\"");
        if (!at) return 0;
        at += 8;
    }
    char path[1024];
    size_t length = strcspn(at, "\"");
    if (length >= sizeof path) return 0;
    memcpy(path, at, length);
    path[length] = 0;
    FILE *file = fopen(path, "rb");
    if (!file) return 0;
    size_t count = 0;
    int ok = 1, c;
    while ((c = fgetc(file)) != EOF) ok &= c == (int)(count++ & 0xFF);
    fclose(file);
    unlink(path);
    return ok && count == size;
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
    // debug.netFetch, as in Debug builds of the app.
    options[count++] = strdup("-Doneechan.diagnostics=true");
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
    oc_jvm_callbacks callbacks = {.context = NULL, .complete = complete, .emit = emit, .fetch = fetch};
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
    char *reply = wait_reply(1);
    printf("hello answered in %.3f s\n", now() - booted);
    print_memory("after hello");
    if (!strstr(reply, getenv("EXPECT_JVM") ? getenv("EXPECT_JVM") : "OpenJ9")) { fprintf(stderr, "FAIL: hello does not name the expected JVM\n"); return 1; }

    // Extension HTTP through the binary channel: bodies cross as raw bytes, both ways.
    const char *init = "{\"id\":\"b\",\"method\":\"host.init\",\"params\":{\"nativeNet\":true}}";
    oc_jvm_call(2, (const uint8_t *)init, strlen(init));
    wait_reply(2);
    char fetches[512];
    snprintf(fetches, sizeof fetches,
             "{\"id\":\"c\",\"method\":\"debug.netFetch\",\"params\":{\"requests\":[{\"url\":\"http://smoke.invalid/bytes\"},"
             "{\"url\":\"http://smoke.invalid/echo\",\"method\":\"POST\",\"bodySize\":%d}]}}", POST_SIZE);
    double fetching = now();
    oc_jvm_call(3, (const uint8_t *)fetches, strlen(fetches));
    reply = wait_reply(3);
    printf("bodies fetched in %.3f s\n", now() - fetching);
    if (bad_request_body) { fprintf(stderr, "FAIL: a request body changed on its way out of the JVM\n"); return 1; }
    if (!body_intact(reply, 0, GET_SIZE) || !body_intact(reply, 1, POST_SIZE)) { fprintf(stderr, "FAIL: a response body changed on its way into the JVM\n"); return 1; }
    printf("HOST SMOKE OK\n");
    fflush(stdout);
    _exit(0);
}
