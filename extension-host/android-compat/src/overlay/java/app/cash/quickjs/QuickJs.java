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
 * run on iOS; this version delegates to the platform JavaScript backend (Rhino by default,
 * JavaScriptCore on Apple platforms).
 */

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import oneechan.host.platform.JsEngine;
import oneechan.host.platform.Platform;

public final class QuickJs implements Closeable {
    private JsEngine engine;

    public static QuickJs create() {
        return new QuickJs();
    }

    public QuickJs() {
        this.engine = Platform.INSTANCE.getJs().create();
    }

    public Object evaluate(String script, String ignoredFileName) {
        return evaluate(script);
    }

    public Object evaluate(String script) {
        try {
            return engine().evaluate(script);
        } catch (QuickJsException e) {
            throw e;
        } catch (Exception e) {
            throw new QuickJsException(e.getMessage(), e);
        }
    }

    public byte[] compile(String sourceCode, String ignoredFileName) {
        return sourceCode.getBytes(StandardCharsets.UTF_8);
    }

    public Object execute(byte[] bytecode) {
        return evaluate(new String(bytecode, StandardCharsets.UTF_8));
    }

    public <T> void set(String name, Class<T> ignoredType, T object) {
        engine().set(name, object);
    }

    private JsEngine engine() {
        if (engine == null) throw new IllegalStateException("QuickJs is closed");
        return engine;
    }

    @Override
    public void close() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception ignored) {
            }
            engine = null;
        }
    }
}
