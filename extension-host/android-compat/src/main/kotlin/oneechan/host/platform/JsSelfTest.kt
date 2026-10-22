package oneechan.host.platform

import app.cash.quickjs.QuickJs
import app.cash.quickjs.QuickJsException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Conformance checks of app.cash.quickjs.QuickJs, run through its public API on an engine: the same
 * suite runs on Rhino in Gradle and on JavaScriptCore inside the app's JVM (the embedded host's
 * `debug.jsSelfTest`). Expected values are those of Android's quickjs-android 0.9.2 (see
 * [JsValues]); scripts in js-selftest/ and the generated packer payloads mirror what extensions
 * evaluate (Dean Edwards' packer, LZString, base64 polyfills, hashes, JSON state blobs).
 */
object JsSelfTest {
    class Result(
        val name: String,
        val passed: Boolean,
        val skipped: Boolean,
        val detail: String,
        val millis: Long,
    )

    class Timing(
        val name: String,
        val engine: String,
        /** Milliseconds per run: the median of [runs] after a warm-up run (just the first run when that is slow). */
        val millis: Double,
        val runs: Int,
    )

    private class Skip(
        reason: String,
    ) : RuntimeException(reason)

    /** A check returns an optional detail (reported with its result). */
    private class Check(
        val name: String,
        val body: Session.() -> String?,
    )

    /** What a check runs with: QuickJs instances backed by the engine under test. */
    class Session(
        val factory: JsEngineFactory,
    ) {
        val isRhino: Boolean get() = factory === RhinoJsEngineFactory

        fun js(): QuickJs = JsEngines.withFactory(factory) { QuickJs.create() }

        fun eval(script: String): Any? = js().use { it.evaluate(script) }
    }

    /** Runs every check (or those whose name starts with [only]) on [factory]. */
    fun run(
        factory: JsEngineFactory = JsEngines.selected,
        only: String? = null,
    ): List<Result> {
        val session = Session(factory)
        return checks.filter { only == null || it.name.startsWith(only) }.map { check ->
            val started = System.nanoTime()
            val (passed, skipped, detail) =
                try {
                    Triple(true, false, check.body(session).orEmpty())
                } catch (e: Skip) {
                    Triple(true, true, e.message.orEmpty())
                } catch (e: Throwable) {
                    Triple(false, false, "${e::class.java.simpleName}: ${e.message}")
                }
            Result(check.name, passed, skipped, detail, (System.nanoTime() - started) / 1_000_000)
        }
    }

    fun summary(results: List<Result>): String =
        "passed=${results.count { it.passed && !it.skipped }} skipped=${results.count { it.skipped }} failed=${results.count { !it.passed }}"

    // --- Assertions.

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private fun show(value: Any?): String =
        when (value) {
            null -> "null"
            is Array<*> -> value.joinToString(", ", "${value.javaClass.simpleName}[", "]") { show(it) }
            is String -> JsValues.quote(if (value.length > 80) value.take(80) + "…(${value.length})" else value)
            else -> "$value (${value.javaClass.simpleName})"
        }

    /** Same Java types and values, Object[] for arrays (Double.equals: -0.0 differs from 0.0, NaN equals NaN). */
    private fun same(
        actual: Any?,
        expected: Any?,
    ): Boolean =
        when {
            expected is Array<*> -> actual is Array<*> && actual.javaClass == Array<Any?>::class.java && actual.size == expected.size && actual.indices.all { same(actual[it], expected[it]) }
            expected == null -> actual == null
            else -> actual != null && actual.javaClass == expected.javaClass && actual == expected
        }

    private fun Session.expect(
        script: String,
        expected: Any?,
    ) {
        val actual = eval(script)
        if (!same(actual, expected)) fail("$script → ${show(actual)}, expected ${show(expected)}")
    }

    private inline fun <reified T : Throwable> expectThrows(
        what: String,
        block: () -> Any?,
    ): T {
        val value =
            try {
                block()
            } catch (e: Throwable) {
                if (e is T) return e
                fail("$what threw ${e::class.java.name}: ${e.message}, expected ${T::class.java.simpleName}")
            }
        fail("$what returned ${show(value)}, expected ${T::class.java.simpleName}")
    }

    private fun Session.expectError(
        script: String,
        message: String?,
    ) {
        val e = expectThrows<QuickJsException>(script) { eval(script) }
        if (message != null && e.message != message) fail("$script: message ${JsValues.quote(e.message.orEmpty())}, expected ${JsValues.quote(message)}")
        if (message == null && e.message.isNullOrEmpty()) fail("$script: empty message")
    }

    // --- Fixtures.

    fun fixture(name: String): String =
        JsSelfTest::class.java.getResourceAsStream("/oneechan/host/platform/js-selftest/$name")?.use { it.readBytes().decodeToString() }
            ?: error("missing fixture $name")

    /** Dean Edwards' packer format, as sites ship page lists: `eval(function(p,a,c,k,e,d){…}('payload',62,n,'words'.split('|'),0,{}))`. */
    object Packer {
        const val DECODER =
            "function(p,a,c,k,e,d){e=function(c){return(c<a?'':e(parseInt(c/a)))+((c=c%a)>35?String.fromCharCode(c+29):c.toString(36))};" +
                "if(!''.replace(/^/,String)){while(c--){d[e(c)]=k[c]||e(c)}k=[function(e){return d[e]}];e=function(){return'\\\\w+'};c=1};" +
                "while(c--){if(k[c]){p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c])}}return p}"

        private val word = Regex("\\b\\w+\\b")

        fun encode(index: Int): String {
            val digit = index % 62
            val last = if (digit > 35) (digit + 29).toChar().toString() else digit.toString(36)
            return (if (index < 62) "" else encode(index / 62)) + last
        }

        fun pack(source: String): String {
            val counts = LinkedHashMap<String, Int>()
            word.findAll(source).forEach { counts.merge(it.value, 1, Int::plus) }
            val words = counts.entries.sortedByDescending { it.value }.map { it.key }
            val codes = HashMap<String, String>()
            words.forEachIndexed { i, w -> codes[w] = encode(i) }
            val payload = word.replace(source) { codes.getValue(it.value) }
            val keywords = words.mapIndexed { i, w -> if (encode(i) == w) "" else w }.joinToString("|")
            return "eval($DECODER('${escape(payload)}',62,${words.size},'$keywords'.split('|'),0,{}))"
        }

        private fun escape(text: String): String = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
    }

    /** A chapter script like those sites pack (image list, ids, a helper). */
    fun chapterScript(pages: Int): String =
        buildString {
            append("var chapterid = 815623; var imagecount = $pages; var guidkey = '6e1f0c2a';\n")
            append("var newImgs = [")
            for (i in 1..pages) {
                if (i > 1) append(',')
                append("'//zjcdn.example-cdn.org/store/manga/31027/03-140.0/compressed/k${"%03d".format(i)}.jpg?token=4f1c9d${i * 7}&ttl=1767225600'")
            }
            append("];\nfunction dm5imagefun() { var pix = newImgs; return pix.map(function (p) { return 'https:' + p; }); }\n")
        }

    /** A large script with many distinct identifiers (the packer's dictionary grows with them). */
    fun bigScript(functions: Int): String =
        buildString {
            for (i in 0 until functions) {
                append("function step$i(input, offset$i) { var value$i = input.charCodeAt(offset$i % input.length) ^ ${i * 31 % 251};")
                append(" return String.fromCharCode(value$i % 128) + 'part$i'; }\n")
            }
            append("var steps = []; for (var n = 0; n < $functions; n++) steps.push(n); var joined = steps.join(',');\n")
        }

    private fun md5(text: String): String = MessageDigest.getInstance("MD5").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    /** The reference LZString 1.5.0 compressToBase64 of [LZ_PLAIN]. */
    private const val LZ_VECTOR =
        "N4IgxgFghgDgLgUwE4gFwBYBMAaEcCWcANgmiADsB2A92AG8AmAlwASC0coOGmLgvBuAAeyLnwBbKAHMEAZzQBtEBDhwYE1AHoVYBpQB0CAB5QhMEloD2SUSpGVRUFVhUAGBwEYtAKxiiBchUtXrNHX1DYzMLKxs7TEcHTHdPb3lFZTUNbT0DIwRTc0soa1t7JwBmeK9cJL9UwIyQ7LC8gqiY9DLE3xSA9OCsnPD8yKKHAFY2io7/NKDM0NyIwuinADYxn2TJmp7Z/qahgHZVys6p2t6G+eanAA5Dierumfq5gYWYgE5b9fvpur7GwcWzgcnyqXR+Z2eu0Brg85TWoJOWyeOwBjmccVhIAAugBfIA==="

    private val LZ_PLAIN: String =
        "{\"chapter\":42,\"title\":\"Ünïcödé 中文 😀\",\"images\":[" +
            (1..12).joinToString(",") { "\"https://cdn.example.org/manga/42/${"%03d".format(it)}.jpg\"" } + "]}"

    // --- Checks.

    private val checks: List<Check> =
        listOf(
            Check("values.numbers") {
                expect("1 + 2", 3)
                expect("'7' * 6", 42)
                expect("5 / 2", 2.5)
                expect("2147483647", Int.MAX_VALUE)
                expect("-2147483648", Int.MIN_VALUE)
                expect("2147483647 + 1", 2147483648.0)
                expect("Math.pow(2, 32)", 4294967296.0)
                expect("-0", -0.0)
                expect("0 * -1", -0.0)
                expect("0.1 + 0.2", 0.30000000000000004)
                expect("NaN", Double.NaN)
                expect("1 / 0", Double.POSITIVE_INFINITY)
                expect("1e21", 1e21)
                expect("Number.MAX_SAFE_INTEGER", 9007199254740991.0)
                expect("parseInt('ff', 16)", 255)
                expect("'abc'.length", 3)
                expect("(1.5).toFixed(2)", "1.50")
                null
            },
            Check("values.scalars") {
                expect("'a' + 'b'", "ab")
                expect("true", true)
                expect("1 > 2", false)
                expect("undefined", null)
                expect("null", null)
                expect("var assigned = 1", null)
                expect("", null)
                expect("void 0", null)
                null
            },
            Check("values.strings") {
                expect("'é中😀'", "é中😀")
                expect("'\\u00e9\\u4e2d\\ud83d\\ude00'", "é中😀")
                expect("'😀'.length", 2)
                expect("String.fromCharCode(0xd83d)", "\ud83d")
                expect("'a\\u0000b'", "a\u0000b")
                expect("'x'.repeat(1 << 22).length", 1 shl 22)
                val big = eval("'ab'.repeat(1 << 20)") as String
                if (big.length != 2 shl 20 || !big.startsWith("abab") || !big.endsWith("ab")) fail("2 MB string came back wrong (${big.length})")
                null
            },
            Check("values.arrays") {
                expect("[1, 'two', true, null, undefined, 2.5, [3, [4]]]", arrayOf(1, "two", true, null, null, 2.5, arrayOf(3, arrayOf(4))))
                expect("[]", arrayOf<Any?>())
                expect("['x', 'y']", arrayOf("x", "y"))
                expect("[1, 2, 3]", arrayOf(1, 2, 3))
                expect("[, 1]", arrayOf(null, 1))
                expect("new Array(2)", arrayOf<Any?>(null, null))
                expect("JSON.parse('[1, [\"a\"]]')", arrayOf(1, arrayOf("a")))
                expect("'a,b'.split(',')", arrayOf("a", "b"))
                expect("[1.5, -0, 2147483648]", arrayOf(1.5, -0.0, 2147483648.0))
                null
            },
            Check("values.unsupported") {
                // Objects, functions and the like have no Java value: null, as QuickJS's evaluate returns.
                for (script in listOf("({a: 1})", "(function () {})", "Symbol('s')", "new Date(0)", "/x/g", "new Uint8Array(2)", "new String('s')", "new Number(1)", "Promise.resolve(1)")) {
                    expect(script, null)
                }
                // Inside an array QuickJS refuses them.
                expectError("[{a: 1}]", "Cannot marshal value [object Object] to Java")
                expectError("[1, [new Date(NaN)]]", "Cannot marshal value Invalid Date to Java")
                val e = expectThrows<QuickJsException>("[function f() {}]") { eval("[function f() {}]") }
                if (e.message?.startsWith("Cannot marshal value function") != true) fail("function in array: ${e.message}")
                null
            },
            Check("errors.messages") {
                expectError("throw new Error('boom')", "boom")
                expectError("throw new TypeError('bad type')", "bad type")
                expectError("throw new Error()", "")
                expectError("throw 'plain'", "plain")
                expectError("throw 42", "42")
                expectError("throw {code: 1}", "[object Object]")
                expectError("throw {message: 'custom'}", "custom")
                expectError("function E(m) { this.message = m } E.prototype = Object.create(Error.prototype); throw new E('sub')", "sub")
                null
            },
            Check("errors.engine") {
                // The texts are each engine's own (QuickJS says "'nope' is not defined"); only presence is portable.
                expectError("nope()", null)
                expectError("null.x", null)
                expectError("this is not javascript", null)
                expectError("JSON.parse('{')", null)
                expectError("new Array(-1)", null)
                expect("try { null.x } catch (e) { e instanceof TypeError }", true)
                expect("try { nope } catch (e) { e instanceof ReferenceError }", true)
                expect("try { throw 1 } catch (e) { e }", 1)
                null
            },
            Check("errors.recover") {
                js().use { js ->
                    js.evaluate("var kept = 41")
                    expectThrows<QuickJsException>("throw") { js.evaluate("kept++; throw new Error('x')") }
                    expectThrows<QuickJsException>("syntax") { js.evaluate("kept = = 1") }
                    val value = js.evaluate("kept + 1")
                    if (value != 43) fail("state after errors: ${show(value)}")
                }
                null
            },
            Check("state.persists") {
                js().use { js ->
                    js.evaluate("var counter = 1")
                    js.evaluate("counter += 41")
                    js.evaluate("function twice(x) { return x * 2 }")
                    js.evaluate("let lexical = 5; const constant = 6;")
                    js.evaluate("globalThis.viaGlobal = 'g'")
                    val values = listOf(js.evaluate("counter"), js.evaluate("twice(21)"), js.evaluate("lexical + constant"), js.evaluate("viaGlobal"))
                    if (values != listOf<Any?>(42, 42, 11, "g")) fail("state across evaluations: $values")
                }
                null
            },
            Check("state.isolated") {
                js().use { a ->
                    js().use { b ->
                        a.evaluate("var secret = 1; Array.prototype.extra = 1")
                        val seen = b.evaluate("typeof secret + ',' + typeof [].extra")
                        if (seen != "undefined,undefined") fail("contexts share state: $seen")
                    }
                }
                null
            },
            Check("state.globals") {
                // A bare QuickJS context: the ECMAScript library only (no console, timers, DOM, host bridges).
                val absent =
                    listOf(
                        "console", "window", "document", "navigator", "location", "setTimeout", "setInterval", "atob", "btoa", "fetch",
                        "XMLHttpRequest", "require", "module", "process", "print", "load", "Packages", "java", "importClass",
                    )
                val present = listOf("JSON", "Math", "Proxy", "Reflect", "Promise", "Map", "WeakMap", "Set", "Symbol", "Uint8Array", "DataView", "escape", "unescape", "encodeURIComponent", "parseFloat", "globalThis")
                val found = eval("[${absent.joinToString(",") { JsValues.quote(it) }}].filter(function (n) { return typeof globalThis[n] !== 'undefined' }).join(',')")
                val missing = eval("[${present.joinToString(",") { JsValues.quote(it) }}].filter(function (n) { return typeof globalThis[n] === 'undefined' }).join(',')")
                if (found != "" || missing != "") fail("unexpected globals [$found], missing [$missing]")
                eval("Object.getOwnPropertyNames(globalThis).sort().join(' ')") as String
            },
            Check("syntax.es2015") {
                features(
                    "let/const" to "let a = 1; const b = 2; a + b === 3",
                    "arrow" to "[1, 2].map(n => n * 2).join() === '2,4'",
                    "template" to "`t${1 + 1}` === 't2'",
                    "destructuring" to "var [p, q = 2] = [1]; var {x, y: {z}} = {x: 1, y: {z: 2}}; p + q + x + z === 6",
                    "array rest pattern" to "var [first, ...others] = [1, 2, 3]; others.length === 2",
                    "rest parameters" to "(function (...args) { return args.length })(1, 2) === 2",
                    "array spread" to "[...'ab', ...[1]].length === 3",
                    "call spread" to "Math.max(...[1, 3, 2]) === 3",
                    "default params" to "(function (a, b = 5) { return a + b })(1) === 6",
                    "class" to "class A { constructor(v) { this.v = v } get twice() { return this.v * 2 } static of(v) { return new A(v) } } " +
                        "class B extends A { get twice() { return super.twice + 1 } } new B(2).twice === 5 && A.of(1).v === 1",
                    "generator" to "function* g() { yield 1; yield 2 } [...g()].length === 2",
                    "for-of" to "var s = 0; for (var v of new Set([1, 2, 2])) s += v; s === 3",
                    "symbol" to "typeof Symbol.iterator === 'symbol'",
                    "map/set" to "new Map([[1, 'a']]).get(1) === 'a' && new Set([1, 1]).size === 1",
                    "proxy/reflect" to "new Proxy({}, {get: function (t, k) { return k }}).hello === 'hello' && Reflect.ownKeys({a: 1}).length === 1",
                    "typed arrays" to "new Uint8Array([256, 1])[0] === 0 && new DataView(new ArrayBuffer(4)).getInt32(0) === 0",
                    "unicode escapes" to "'\\u{1F600}' === '😀' && /\\u{1F600}/u.test('😀')",
                    "object literal" to "var k = 'dyn'; var o = {[k]: 1, m() { return 2 }}; o.dyn + o.m() === 3",
                    "promise" to "typeof Promise.resolve(1).then === 'function'",
                )
            },
            Check("syntax.es2016+") {
                features(
                    "exponent" to "2 ** 10 === 1024",
                    "includes" to "[1, 2].includes(2) && 'abc'.includes('b')",
                    "padStart" to "'5'.padStart(3, '0') === '005'",
                    "entries" to "Object.entries({a: 1}).length === 1 && Object.values({a: 1})[0] === 1",
                    "async syntax" to "typeof (async function () { await 1 }) === 'function'",
                    "object spread" to "({...{a: 1}, b: 2}).a === 1",
                    "named groups" to "/(?<year>\\d{4})/.exec('in 2024').groups.year === '2024'",
                    "lookbehind" to "/(?<=\\$)\\d+/.exec('cost $42')[0] === '42'",
                    "dotAll" to "/a.b/s.test('a\\nb')",
                    "flat" to "[1, [2, [3]]].flat(Infinity).length === 3",
                    "fromEntries" to "Object.fromEntries([['a', 1]]).a === 1",
                    "optional catch" to "(function () { try { throw 1 } catch { return true } })()",
                    "nullish" to "(null ?? 'd') === 'd'",
                    "optional chaining" to "({}).missing?.deep === undefined",
                    "bigint" to "typeof BigInt(1) === 'bigint'",
                    "replaceAll" to "'a-b-c'.replaceAll('-', '+') === 'a+b+c'",
                    "globalThis" to "typeof globalThis === 'object'",
                )
            },
            Check("api.compile") {
                js().use { js ->
                    val bytecode = js.compile("var compiled = 3; compiled + 4", "compiled.js")
                    if (js.evaluate("typeof compiled") != "undefined") fail("compile ran the script")
                    if (js.execute(bytecode) != 7) fail("execute of compiled code")
                    // Bytecode may be cached and run in another instance (en.mangago does).
                    js().use { other -> if (other.execute(bytecode) != 7) fail("execute in another instance") }
                    expectThrows<QuickJsException>("compile of a syntax error") { js.compile("this is not javascript", "bad.js") }
                }
                null
            },
            Check("api.lifecycle") {
                val js = js()
                if (js.evaluate("1", "named.js") != 1) fail("evaluate with a file name")
                js.close()
                js.close()
                expectThrows<IllegalStateException>("evaluate after close") { js.evaluate("1") }
                null
            },
            Check("api.set") {
                js().use { js ->
                    js.set("calc", JsSelfTestHost::class.java, JsSelfTestHost.Impl())
                    expectThrows<IllegalArgumentException>("set of an existing global") { js.set("calc", JsSelfTestHost::class.java, JsSelfTestHost.Impl()) }
                    expectThrows<UnsupportedOperationException>("set of a class") { js.set("text", String::class.java, "x") }
                    val results =
                        listOf(
                            js.evaluate("calc.add(2, 3)"), js.evaluate("calc.greet('x')"), js.evaluate("calc.ratio(3)"), js.evaluate("calc.flag(true)"),
                            js.evaluate("typeof calc.log('m')"), js.evaluate("calc.list()"), js.evaluate("calc.echo([1, 'a'])"), js.evaluate("typeof calc.add"),
                            js.evaluate("try { calc.add(1) } catch (e) { e instanceof RangeError }"), js.evaluate("try { calc.fail() } catch (e) { 'caught' }"),
                        )
                    val expected = listOf<Any?>(5, "hi x", 1.5, false, "undefined", arrayOf("a", "b"), arrayOf<Any?>(1, "a"), "function", true, "caught")
                    for (i in results.indices) if (!same(results[i], expected[i])) fail("set: case $i → ${show(results[i])}, expected ${show(expected[i])}")
                    // As on Android, an exception from a host method escapes unchanged, and a bad argument is the converter's.
                    val thrown = expectThrows<IllegalStateException>("host exception") { js.evaluate("calc.fail()") }
                    if (thrown.message != "host failure") fail("host exception message ${thrown.message}")
                    expectThrows<IllegalArgumentException>("int argument 1.5") { js.evaluate("calc.add(1.5, 2)") }
                }
                null
            },
            Check("api.get") {
                js().use { js ->
                    js.evaluate("var greeter = { hello: function (n) { return 'hello ' + n }, sum: function (a, b) { return a + b }, half: function (n) { return n / 2 } }")
                    val proxy = js.get("greeter", JsSelfTestGuest::class.java)
                    val results = listOf<Any?>(proxy.hello("x\"y"), proxy.sum(2, 3), proxy.half(3.0))
                    if (results != listOf<Any?>("hello x\"y", 5, 1.5)) fail("get: $results")
                    expectThrows<IllegalArgumentException>("get of a missing global") { js.get("missing", JsSelfTestGuest::class.java) }
                    js.evaluate("var partial = { hello: function () { return '' } }")
                    expectThrows<QuickJsException>("get without a method") { js.get("partial", JsSelfTestGuest::class.java) }
                }
                null
            },
            Check("snippets.packer") {
                // en.mangahere, th.doodmanga: evaluate(script.removePrefix("eval")) returns the unpacked source.
                val source = chapterScript(24)
                val packed = Packer.pack(source)
                val unpacked = eval(packed.removePrefix("eval"))
                if (unpacked != source) fail("unpacked source differs: ${show(unpacked)}")
                js().use { js ->
                    js.evaluate(packed)
                    val pages = js.evaluate("dm5imagefun()") as Array<*>
                    if (pages.size != 24 || pages[23] != "https://zjcdn.example-cdn.org/store/manga/31027/03-140.0/compressed/k024.jpg?token=4f1c9d168&ttl=1767225600") {
                        fail("pages after eval: ${show(pages)}")
                    }
                }
                "packed ${packed.length} chars"
            },
            Check("snippets.lzstring") {
                js().use { js ->
                    js.evaluate(fixture("lzstring.js"))
                    val decoded = js.evaluate("LZ.decompressFromBase64(${JsValues.quote(LZ_VECTOR)})")
                    if (decoded != LZ_PLAIN) fail("decompress of the reference vector: ${show(decoded)}")
                    val again = js.evaluate("LZ.compressToBase64(${JsValues.quote(LZ_PLAIN)})")
                    if (again != LZ_VECTOR) fail("compress differs from LZString: ${show(again)}")
                    val roundTrip = js.evaluate("var big = ''; for (var i = 0; i < 3000; i++) big += 'page-' + (i * 7919 % 1000) + ';'; LZ.decompressFromBase64(LZ.compressToBase64(big)) === big")
                    if (roundTrip != true) fail("round trip of a large text")
                }
                null
            },
            Check("snippets.base64") {
                js().use { js ->
                    js.evaluate(fixture("base64.js"))
                    val text = "Ünïcödé 中文 😀 — page list"
                    val encoded = java.util.Base64.getEncoder().encodeToString(text.encodeToByteArray())
                    val results = listOf(js.evaluate("Base64.decodeText('$encoded')"), js.evaluate("Base64.encodeText(${JsValues.quote(text)})"), js.evaluate("Base64.atob('aGk=')"))
                    if (results != listOf(text, encoded, "hi")) fail("base64: $results")
                    expectThrows<QuickJsException>("invalid base64") { js.evaluate("Base64.atob('@@@')") }
                }
                null
            },
            Check("snippets.md5") {
                js().use { js ->
                    js.evaluate(fixture("md5.js"))
                    for (text in listOf("", "abc", "Ünïcödé 中文 😀", "chapter-".repeat(500))) {
                        val hash = js.evaluate("md5(${JsValues.quote(text)})")
                        if (hash != md5(text)) fail("md5(${text.take(20)}) = $hash, expected ${md5(text)}")
                    }
                }
                null
            },
            Check("snippets.json") {
                // zh.kuaikanmanhua: window.__NUXT__ state; en.* (14 themes): Object.fromEntries over a list.
                js().use { js ->
                    js.evaluate("var window = {};")
                    js.evaluate("window.__NUXT__ = (function (a, b) { return {data: [{comic: {id: a, title: b, tags: ['热血', '冒险']}}], state: {ok: !0}} })(1024, '标题 \"q\"');")
                    val state = js.evaluate("JSON.stringify(window.__NUXT__)")
                    if (state != "{\"data\":[{\"comic\":{\"id\":1024,\"title\":\"标题 \\\"q\\\"\",\"tags\":[\"热血\",\"冒险\"]}}],\"state\":{\"ok\":true}}") fail("NUXT state: $state")
                    val map = js.evaluate("JSON.stringify(Object.fromEntries([{displayName: 'Status', value: 'ongoing'}, {displayName: 'Type', value: 3}].map(i => [i.displayName, i.value])))")
                    if (map != "{\"Status\":\"ongoing\",\"Type\":3}") fail("fromEntries: $map")
                    val big = js.evaluate("var doc = {pages: []}; for (var i = 0; i < 5000; i++) doc.pages.push({i: i, url: 'https://x.org/' + i + '.webp', w: 0.5 * i}); var s = JSON.stringify(doc); JSON.stringify(JSON.parse(s)) === s && s.length")
                    if (big !is Int || big < 200_000) fail("JSON round trip: ${show(big)}")
                }
                null
            },
            Check("snippets.page-scripts") {
                // Shapes from the extensions: a DOM stub driven by a Proxy (zh.tencentcomics), a cookie
                // challenge with an immediate setTimeout (es.onfmangas), a string-table decoder (zh.comicabc).
                val tencent =
                    eval(
                        "var window = this; var document = new Proxy(function () {}, { get: function () { return document }, apply: function () { return document } });" +
                            "var raw = 'abcdef'.split(''); var nonce = '2a3b'.match(/\\d+[a-zA-Z]+/g); var len = nonce.length;" +
                            "while (len--) { var offset = parseInt(nonce[len]) & 255; var noise = nonce[len].replace(/\\d+/g, ''); raw.splice(offset, noise.length); }" +
                            "document.createElement('a').href; raw.join('')",
                    )
                val cookie =
                    eval(
                        "var window = { location: {} }; var document = { cookie: null }; var location = window.location;" +
                            "var setTimeout = function (fn, _) { fn(); };" +
                            "setTimeout(function () { document.cookie = 'cf=' + (6 * 7) + '; path=/'; location.href = '/next'; }, 1000);" +
                            "JSON.stringify({cookie: document.cookie, href: location.href})",
                    )
                val table =
                    eval(
                        "function lc(l) { if (l.length != 2) return l; var az = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'; var a = l.substring(0, 1); var b = l.substring(1, 2);" +
                            " if (a == 'Z') return 8000 + az.indexOf(b); else return az.indexOf(a) * 52 + az.indexOf(b); }" +
                            "[lc('ab'), lc('Zc'), lc('x')]",
                    )
                if (tencent != "abef" || cookie != "{\"cookie\":\"cf=42; path=/\",\"href\":\"/next\"}" || !same(table, arrayOf<Any?>(1, 8002, "x"))) {
                    fail("page scripts: ${show(tencent)} ${show(cookie)} ${show(table)}")
                }
                null
            },
            Check("limits.recursion") {
                js().use { js ->
                    js.evaluate("function depth(n) { return n === 0 ? 0 : 1 + depth(n - 1) }")
                    if (js.evaluate("depth(1000)") != 1000) fail("recursion to 1000")
                    expectThrows<QuickJsException>("runaway recursion") { js.evaluate("function forever() { return forever() + 1 } forever()") }
                    var reached = 1000
                    for (n in listOf(5_000, 20_000, 100_000)) {
                        val ok = runCatching { js.evaluate("depth($n)") == n }.getOrDefault(false)
                        if (!ok) break
                        reached = n
                    }
                    if (js.evaluate("depth(10)") != 10) fail("context unusable after a stack overflow")
                    "recursion depth ≥ $reached"
                }
            },
            Check("threads.concurrent") {
                val pool = Executors.newFixedThreadPool(8)
                try {
                    val tasks =
                        (0 until 8).map { worker ->
                            Callable {
                                js().use { js ->
                                    js.evaluate("var me = $worker")
                                    (0 until 25).map { js.evaluate("var s = 0; for (var i = 0; i < 2000; i++) s += i; s + me * 1000000") }.distinct()
                                }
                            }
                        }
                    val results = pool.invokeAll(tasks, 5, TimeUnit.MINUTES).mapIndexed { i, f -> i to f.get() }
                    for ((worker, values) in results) if (values != listOf(1_999_000 + worker * 1_000_000)) fail("worker $worker: $values")
                } finally {
                    pool.shutdownNow()
                }
                // One instance used from several threads in turn.
                val shared = js()
                shared.use {
                    val other = Executors.newSingleThreadExecutor()
                    try {
                        shared.evaluate("var handoff = 1")
                        val value = other.submit(Callable { shared.evaluate("handoff + 1") }).get(1, TimeUnit.MINUTES)
                        if (value != 2) fail("cross-thread use: $value")
                    } finally {
                        other.shutdownNow()
                    }
                }
                null
            },
            Check("lifecycle.cleaner") {
                if (factory !== JavaScriptCoreJsEngineFactory) throw Skip("native contexts only")
                val before = JavaScriptCoreJsEngineFactory.liveContexts
                dropOpenEngines(this, 16)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (JavaScriptCoreJsEngineFactory.liveContexts > before && System.nanoTime() < deadline) {
                    System.gc()
                    Thread.sleep(100)
                }
                val left = JavaScriptCoreJsEngineFactory.liveContexts - before
                if (left > 0) fail("$left dropped contexts were not released")
                "live contexts back to $before"
            },
        )

    private fun dropOpenEngines(
        session: Session,
        count: Int,
    ) {
        repeat(count) { session.js().evaluate("var leaked = $it") }
    }

    /** Runs feature probes; Rhino's known gaps are reported instead of failed. */
    private fun Session.features(vararg cases: Pair<String, String>): String? {
        val failed = ArrayList<String>()
        for ((name, script) in cases) {
            val ok = runCatching { js().use { it.evaluate(script) } == true }.getOrDefault(false)
            if (!ok) failed += name
        }
        if (failed.isEmpty()) return null
        val unexpected = if (isRhino) failed - RHINO_GAPS else failed
        if (unexpected.isNotEmpty()) fail("unsupported: ${unexpected.joinToString()}")
        return "Rhino lacks: ${failed.joinToString()}"
    }

    /** Language features Rhino 1.9 does not implement (QuickJS 2021-03-27 and JavaScriptCore do). */
    private val RHINO_GAPS = setOf("array rest pattern", "call spread", "class", "async syntax")

    // --- Benchmarks.

    private class Bench(
        val name: String,
        val setup: (QuickJs) -> Unit = {},
        val body: (QuickJs) -> Unit,
    )

    private val benches: List<Bench> by lazy {
        val packed = Packer.pack(bigScript(600))
        val chapter = Packer.pack(chapterScript(60))
        val lzText = (0 until 4000).joinToString(";") { "page-${it * 7919 % 1000}-${it % 37}" }
        listOf(
            Bench("packer-unpack ${packed.length / 1024} KB") { js -> check((js.evaluate(packed.removePrefix("eval")) as String).length > 10_000) },
            Bench("packer-chapter") { js -> js.evaluate(chapter.removePrefix("eval")) },
            Bench(
                "lzstring ${lzText.length / 1024} KB",
                setup = { js -> js.evaluate(fixture("lzstring.js")); js.evaluate("var packedText = LZ.compressToBase64(${JsValues.quote(lzText)})") },
            ) { js -> check(js.evaluate("LZ.decompressFromBase64(packedText).length") == lzText.length) },
            Bench("md5 64 KB", setup = { js -> js.evaluate(fixture("md5.js")) }) { js -> js.evaluate("md5('chapter-page-'.repeat(5000))") },
            Bench("json 300 KB") { js ->
                js.evaluate("var doc = {pages: []}; for (var i = 0; i < 5000; i++) doc.pages.push({i: i, url: 'https://x.org/' + i + '.webp', w: 0.5 * i}); JSON.parse(JSON.stringify(doc)).pages.length")
            },
            Bench("context create+close") { _ -> JsEngines.create().close() },
        )
    }

    /** Times each benchmark (or those whose name starts with [only]) on [factory]. */
    fun benchmark(
        factory: JsEngineFactory,
        only: String? = null,
    ): List<Timing> =
        benches.filter { only == null || it.name.startsWith(only) }.map { bench ->
            JsEngines.withFactory(factory) {
                QuickJs.create().use { js ->
                    bench.setup(js)
                    val first = timed { bench.body(js) }
                    // Slow engines (Rhino on the interpreter-only JVM) are measured once.
                    if (first > 3_000.0) {
                        Timing(bench.name, factory.name, first, 1)
                    } else {
                        val runs = (1..5).map { timed { bench.body(js) } }.sorted()
                        Timing(bench.name, factory.name, runs[runs.size / 2], runs.size)
                    }
                }
            }
        }

    private inline fun timed(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1e6
    }
}

/** A host object for the `api.set` check (quickjs-android's supported types). */
interface JsSelfTestHost {
    fun add(
        a: Int,
        b: Int,
    ): Int

    fun greet(name: String): String

    fun ratio(value: Double): Double

    fun flag(value: Boolean): Boolean

    fun log(message: String)

    fun list(): Array<String>

    fun echo(values: Array<Any?>): Array<Any?>

    fun fail(): String

    class Impl : JsSelfTestHost {
        override fun add(
            a: Int,
            b: Int,
        ) = a + b

        override fun greet(name: String) = "hi $name"

        override fun ratio(value: Double) = value / 2

        override fun flag(value: Boolean) = !value

        override fun log(message: String) = Unit

        override fun list() = arrayOf("a", "b")

        override fun echo(values: Array<Any?>) = values

        override fun fail(): String = throw IllegalStateException("host failure")
    }
}

/** A JavaScript object seen from Java for the `api.get` check. */
interface JsSelfTestGuest {
    fun hello(name: String): String

    fun sum(
        a: Int,
        b: Int,
    ): Int

    fun half(value: Double): Double
}
