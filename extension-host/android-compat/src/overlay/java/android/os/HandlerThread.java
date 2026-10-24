/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Oneechan: the public API of AOSP's android.os.HandlerThread (from the API
 * signature), implemented on AndroidCompat's Looper: the thread prepares a Looper, calls
 * onLooperPrepared() and loops until quit() or quitSafely(). Thread priorities are not applied
 * (the JVM's scheduler decides), and getThreadId() is the Java thread id: android.system.Os is
 * not available for the kernel's.
 */

package android.os;

/** A thread with a {@link Looper}, for {@link Handler}s to post to. */
public class HandlerThread extends Thread {
    private final int priority;
    private volatile int threadId = -1;
    private Looper looper; // guarded by this

    public HandlerThread(String name) {
        this(name, Process.THREAD_PRIORITY_DEFAULT);
    }

    /** @param priority an android.os.Process priority; recorded, not applied. */
    public HandlerThread(String name, int priority) {
        super(name);
        this.priority = priority;
    }

    /** Called on the thread once its Looper is ready, before it starts looping. */
    protected void onLooperPrepared() {
    }

    @Override
    public void run() {
        threadId = (int) getId();
        Looper.prepare();
        synchronized (this) {
            looper = Looper.myLooper();
            notifyAll();
        }
        onLooperPrepared();
        Looper.loop();
        threadId = -1;
    }

    /**
     * The thread's Looper, waiting until the started thread has prepared it; null when the thread was
     * not started or has ended.
     */
    public Looper getLooper() {
        if (!isAlive()) return null;
        boolean interrupted = false;
        synchronized (this) {
            while (isAlive() && looper == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return isAlive() || looper == null ? looper : null;
    }

    /** Stops the Looper now, dropping pending messages; false when the thread is not running. */
    public boolean quit() {
        Looper looper = getLooper();
        if (looper == null) return false;
        looper.quit();
        return true;
    }

    /** Stops the Looper once the messages already due have run; false when the thread is not running. */
    public boolean quitSafely() {
        Looper looper = getLooper();
        if (looper == null) return false;
        looper.quitSafely();
        return true;
    }

    /** The thread's id while it runs, else -1. */
    public int getThreadId() {
        return threadId;
    }

    @Override
    public String toString() {
        return "HandlerThread(" + getName() + ", priority " + priority + ")";
    }
}
