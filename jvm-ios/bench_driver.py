#!/usr/bin/env python3
"""S0 benchmark driver for the Oneechan extension host (stdlib only; run with python3 -I).

One run = fresh data dir, start host, time:
  a) JVM start -> GET /health 200      b) first host.hello
  c) repo.add keiyoushi                d) ext.install mangadex forceApk (request -> DONE event,
                                          plus per-phase split from the SSE ext.progress events)
  e) first source.popular (MangaDex en) f) second source.popular page 1 (same request, warm JVM;
                                          the host answers it from its cache) f2) popular page 2 (warm, uncached)
  g) peak RSS (ps sampling every 100 ms) and total CPU seconds of the JVM process.
Appends one JSON object per run to --out. `--table FILE` prints a markdown table instead.
"""
import argparse, http.client, json, os, shutil, signal, socket, subprocess, sys, threading, time, urllib.request

REPO = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
PKG = "eu.kanade.tachiyomi.extension.all.mangadex"
PHASES = ["QUEUED", "DOWNLOAD", "VERIFY", "CONVERT", "LOAD", "DONE", "FAILED"]


def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


class Host:
    def __init__(self, port):
        self.base = f"http://127.0.0.1:{port}"; self.port = port; self.n = 0

    def rpc(self, method, params=None, timeout=3600):
        self.n += 1
        body = json.dumps({"id": str(self.n), "method": method, "params": params or {}}).encode()
        req = urllib.request.Request(self.base + "/rpc", data=body, headers={"Content-Type": "application/json"})
        t = time.monotonic()
        with urllib.request.urlopen(req, timeout=timeout) as r:
            resp = json.loads(r.read())
        dt = time.monotonic() - t
        if resp.get("error"):
            raise RuntimeError(f"{method}: {resp['error']}")
        return dt, resp.get("result")


class Sse(threading.Thread):
    """Collects (monotonic_time, event_name, data) from GET /events."""
    def __init__(self, port):
        super().__init__(daemon=True); self.port = port; self.events = []; self.connected = threading.Event()
        self.cv = threading.Condition()

    def run(self):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=None)
        c.request("GET", "/events"); r = c.getresponse(); name = None
        while True:
            line = r.readline()  # HTTPResponse.readline decodes the chunked transfer encoding
            if not line:
                return
            line = line.decode().rstrip("\r\n")
            if line.startswith(": connected"):
                self.connected.set()
            elif line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                ev = json.loads(line[5:].strip())
                with self.cv:
                    self.events.append((time.monotonic(), name, ev.get("data", ev))); self.cv.notify_all()

    def wait_job(self, job_id, timeout):
        end = time.monotonic() + timeout
        with self.cv:
            while True:
                for t, n, d in self.events:
                    if n == "ext.progress" and d.get("jobId") == job_id and d.get("phase") in ("DONE", "FAILED"):
                        return d
                left = end - time.monotonic()
                if left <= 0:
                    raise TimeoutError("install did not finish")
                self.cv.wait(left)


class RssSampler(threading.Thread):
    def __init__(self, pid):
        super().__init__(daemon=True); self.pid = pid; self.peak_kb = 0; self.stop = False; self.cpu = None

    def sample(self):
        out = subprocess.run(["ps", "-o", "rss=,time=", "-p", str(self.pid)], capture_output=True, text=True).stdout.split()
        if len(out) >= 2:
            self.peak_kb = max(self.peak_kb, int(out[0])); self.cpu = cpu_secs(out[1])

    def run(self):
        while not self.stop:
            self.sample(); time.sleep(0.1)


class SimProcess:
    """Popen-like handle for an app launched with `xcrun simctl launch` (a host process on macOS)."""
    def __init__(self, pid, udid, bundle):
        self.pid, self.udid, self.bundle, self.returncode = pid, udid, bundle, None

    def poll(self):
        try:
            os.kill(self.pid, 0); return None
        except ProcessLookupError:
            self.returncode = -1; return -1

    def send_signal(self, _sig):
        subprocess.run(["xcrun", "simctl", "terminate", self.udid, self.bundle], capture_output=True)

    def wait(self, timeout=None):
        end = time.monotonic() + (timeout or 30)
        while self.poll() is None:
            if time.monotonic() > end:
                raise subprocess.TimeoutExpired("simctl", timeout)
            time.sleep(0.1)

    def kill(self):
        os.kill(self.pid, signal.SIGKILL)


def cpu_secs(s):  # ps time= -> [[dd-]hh:]mm:ss.cc
    days = 0
    if "-" in s:
        d, s = s.split("-", 1); days = int(d)
    parts = [float(p) for p in s.split(":")]
    secs = 0.0
    for p in parts:
        secs = secs * 60 + p
    return days * 86400 + secs


def one_run(a, run_idx):
    data = os.path.join(a.workdir, f"{a.label}-{run_idx}")
    shutil.rmtree(data, ignore_errors=True); os.makedirs(data)
    port = free_port()
    launch = ["-cp", f"{a.jar}{os.pathsep}{a.cp}", a.main] if a.main else ["-jar", a.jar]
    cmd = [a.java, *a.jvm_args, *launch, "--data", data, "--port", str(port)]
    log = open(os.path.join(a.workdir, f"{a.label}-{run_idx}.log"), "w")
    log.write(" ".join(cmd) + "\n"); log.flush()
    res = {"label": a.label, "run": run_idx, "cmd": cmd, "ts": time.strftime("%Y-%m-%dT%H:%M:%S")}
    if a.sim_udid:  # the host runs inside the iOS-simulator spike app (static Zero JVM)
        cmd = ["xcrun", "simctl", "launch", "--terminate-running-process", f"--stdout={log.name}", f"--stderr={log.name}",
               a.sim_udid, a.sim_bundle, "--host", str(port), f"{a.label}-{run_idx}".replace(" ", "_")]
        res["cmd"] = cmd
    t0 = time.monotonic()
    if a.sim_udid:
        out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
        p = SimProcess(int(out.strip().rsplit(":", 1)[1]), a.sim_udid, a.sim_bundle)
    else:
        p = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
    rss = RssSampler(p.pid); rss.start()
    try:
        while True:  # a) start -> /health
            if p.poll() is not None:
                raise RuntimeError(f"JVM exited early with {p.returncode}; see log")
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
                    if r.status == 200:
                        break
            except Exception:
                time.sleep(0.02)
        res["a_start_health_s"] = time.monotonic() - t0
        sse = Sse(port); sse.start(); sse.connected.wait(600)
        h = Host(port)
        res["b_hello_s"], hello = h.rpc("host.hello"); res["host_version"] = hello.get("hostVersion")
        res["c_repo_add_s"], _ = h.rpc("repo.add", {"url": REPO})
        if a.stop_after == "repo":  # e.g. CDS training without loading any extension classes
            return res
        t_req = time.monotonic()
        _, job = h.rpc("ext.install", {"pkgName": PKG, "forceApk": True})
        done = sse.wait_job(job["jobId"], a.timeout)
        t_done = time.monotonic()
        res["d_install_s"] = t_done - t_req
        res["install_result"] = done.get("phase")
        if done.get("phase") != "DONE":
            res["install_error"] = done.get("error")
        first = {}
        for t, n, d in sse.events:
            if n == "ext.progress" and d.get("jobId") == job["jobId"]:
                first.setdefault(d["phase"], t)
        seq = [ph for ph in PHASES if ph in first]
        res["phases_s"] = {f"{x}->{y}": first[y] - first[x] for x, y in zip(seq, seq[1:])}
        if done.get("phase") == "DONE":
            _, sources = h.rpc("source.list")
            src = next(s for s in sources if s["pkgName"] == PKG and s["lang"] == "en")
            res["source_id"] = src["id"]
            res["e_popular1_s"], page = h.rpc("source.popular", {"sourceId": src["id"], "page": 1})
            res["popular_count"] = len(page.get("mangas", []))
            res["f_popular2_s"], _ = h.rpc("source.popular", {"sourceId": src["id"], "page": 1})
            # page 1 again is served from the host's cache; page 2 is a warm-JVM call that really hits the network+parser
            res["f2_popular_p2_s"], _ = h.rpc("source.popular", {"sourceId": src["id"], "page": 2})
    except Exception as e:
        res["error"] = repr(e)
    finally:
        rss.sample(); rss.stop = True
        res["g_peak_rss_mb"] = round(rss.peak_kb / 1024, 1)
        res["cpu_s"] = rss.cpu
        res["wall_s"] = time.monotonic() - t0
        if p.poll() is None:
            p.send_signal(signal.SIGTERM)
            try:
                p.wait(30)
            except subprocess.TimeoutExpired:
                p.kill(); p.wait()
        log.close()
    return res


def fmt(v):
    return "" if v is None else (f"{v:.2f}" if isinstance(v, float) else str(v))


def table(path):
    rows = [json.loads(l) for l in open(path) if l.strip()]
    cols = ["a_start_health_s", "b_hello_s", "c_repo_add_s", "d_install_s", "DOWNLOAD->VERIFY", "CONVERT->LOAD",
            "LOAD->DONE", "e_popular1_s", "f_popular2_s", "f2_popular_p2_s", "g_peak_rss_mb", "cpu_s"]
    print("| config | run | a start | b hello | c repo.add | d install | dl | convert | load | e popular p1 | f p1 again | f2 p2 | g RSS MB | CPU s |")
    print("|---|---|" + "---|" * (len(cols)))
    for r in rows:
        ph = r.get("phases_s", {})
        vals = [r.get(c, ph.get(c)) for c in cols]
        note = f" ERR {r['error'][:60]}" if r.get("error") else ""
        print(f"| {r['label']}{note} | {r['run']} | " + " | ".join(fmt(v) for v in vals) + " |")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--table"); ap.add_argument("--java"); ap.add_argument("--jar"); ap.add_argument("--label")
    ap.add_argument("--runs", type=int, default=2); ap.add_argument("--out"); ap.add_argument("--workdir")
    ap.add_argument("--timeout", type=float, default=3600)
    ap.add_argument("--main", help="main class instead of -jar (e.g. NoGuardHostLauncher for OpenJ9)")
    ap.add_argument("--cp", default="", help="extra classpath entries for --main")
    ap.add_argument("--stop-after", default="", help="'repo': stop after repo.add (no extension install)")
    ap.add_argument("--sim-udid", help="run the host inside the spike app on this iOS simulator (see bench-sim.sh)")
    ap.add_argument("--sim-bundle", default="dev.oneechan.jvmspike")
    ap.add_argument("jvm_args", nargs="*")
    a = ap.parse_args()
    if a.table:
        return table(a.table)
    os.makedirs(a.workdir, exist_ok=True)
    for i in range(1, a.runs + 1):
        r = one_run(a, i)
        print(json.dumps(r), flush=True)
        with open(a.out, "a") as f:
            f.write(json.dumps(r) + "\n")


if __name__ == "__main__":
    sys.exit(main())
