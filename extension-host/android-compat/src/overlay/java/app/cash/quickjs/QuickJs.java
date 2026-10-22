package app.cash.quickjs;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Modified for Oneechan: overlay of Suwayomi AndroidCompat's QuickJs
 * (AndroidCompat/src/main/java/app/cash/quickjs/QuickJs.java). Upstream binds GraalJS, which cannot
 * run on iOS; this version delegates to the platform JavaScript engine (JavaScriptCore in the app,
 * Rhino elsewhere) with the semantics of Android's quickjs-android 0.9.2: its value conversions,
 * error messages, compile/execute, and interface-only set/get.
 */

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import oneechan.host.platform.JsEngine;
import oneechan.host.platform.JsHostObject;
import oneechan.host.platform.JsProxies;
import oneechan.host.platform.Platform;

public final class QuickJs implements Closeable {
    private volatile JsEngine engine;

    public static QuickJs create() {
        return new QuickJs();
    }

    public QuickJs() {
        this.engine = Platform.INSTANCE.getJs().create();
    }

    /** Evaluates {@code script}; {@code fileName} is used in error reporting. */
    public Object evaluate(String script, String fileName) {
        return engine().evaluate(script, fileName);
    }

    public Object evaluate(String script) {
        return evaluate(script, "?");
    }

    /**
     * Provides {@code object} to JavaScript as a global object called {@code name}, with the methods
     * of {@code type}: an interface that extends no other and has no overloaded methods.
     */
    public <T> void set(String name, Class<T> type, T object) {
        engine().bind(new JsHostObject(name, type, object));
    }

    /** A proxy of {@code type} whose methods call those of the global JavaScript object {@code name}. */
    public <T> T get(String name, Class<T> type) {
        engine();
        return JsProxies.INSTANCE.create(this::engine, name, type);
    }

    /**
     * "Compiles" {@code sourceCode}: syntax errors are reported here, as on Android, and the returned
     * bytes (the UTF-8 source) can be executed by any QuickJs instance.
     */
    public byte[] compile(String sourceCode, String fileName) {
        engine().compile(sourceCode, fileName);
        return sourceCode.getBytes(StandardCharsets.UTF_8);
    }

    public Object execute(byte[] bytecode) {
        return evaluate(new String(bytecode, StandardCharsets.UTF_8), "?");
    }

    private JsEngine engine() {
        JsEngine current = engine;
        if (current == null) throw new IllegalStateException("QuickJs is closed");
        return current;
    }

    @Override
    public void close() {
        JsEngine current = engine;
        engine = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
    }
}
