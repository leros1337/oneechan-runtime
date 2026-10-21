package oneechan.stubs

// Model and parser for API signature files in metalava "Signature format: 2.0" (the format of
// AOSP frameworks/base api/current.txt for Android 11). One declaration per line:
//
//   package android.util {
//     public final class ArrayMap<K, V> implements java.util.Map<K,V> {
//       ctor public ArrayMap(int);
//       method @Nullable public V get(Object);
//       field public static final int FOO = 1; // 0x1
//     }
//   }
//
// Names are fully qualified except java.lang types, which are written without their package, and
// type variables. Nested classes are separate entries named Outer.Inner. Parameter names are absent.

enum class ClassKind(val keyword: String) {
    CLASS("class"),
    INTERFACE("interface"),
    ENUM("enum"),
    ANNOTATION("@interface"),
}

/** A type exactly as written in the signature file. */
sealed interface TypeRef

data class PrimitiveType(val name: String) : TypeRef

/** A class or type-variable reference: the dotted [name] as written, plus type arguments. */
data class ClassType(val name: String, val args: List<TypeRef> = emptyList()) : TypeRef

data class ArrayType(val component: TypeRef, val varargs: Boolean = false) : TypeRef

/** `?`, `? extends bound` or (with [superBound]) `? super bound`. */
data class WildcardType(val bound: TypeRef? = null, val superBound: Boolean = false) : TypeRef

data class TypeParam(val name: String, val bounds: List<TypeRef>)

/** An annotation use; [args] is the raw text between the parentheses, if any. */
data class AnnotationUse(val name: String, val args: String? = null)

data class Param(val type: TypeRef, val annotations: List<AnnotationUse>)

data class CtorItem(
    val modifiers: Set<String>,
    val annotations: List<AnnotationUse>,
    val typeParams: List<TypeParam>,
    val params: List<Param>,
    val throws: List<TypeRef>,
)

data class MethodItem(
    val name: String,
    val modifiers: Set<String>,
    val annotations: List<AnnotationUse>,
    val typeParams: List<TypeParam>,
    val returnType: TypeRef,
    val params: List<Param>,
    val throws: List<TypeRef>,
    /** Raw annotation-method default value (`default false;`). */
    val defaultValue: String? = null,
)

data class FieldItem(
    val name: String,
    val modifiers: Set<String>,
    val annotations: List<AnnotationUse>,
    val type: TypeRef,
    /** Raw constant value (`= 1`, `= "x"`, `= (0.0f/0.0f)`), absent for non-constant fields. */
    val value: String? = null,
)

data class EnumConstantItem(val name: String, val annotations: List<AnnotationUse>)

class ClassItem(
    val pkg: String,
    /** Name inside the package, dotted for nested classes (`View.OnClickListener`). */
    val name: String,
    val kind: ClassKind,
    val modifiers: Set<String>,
    val annotations: List<AnnotationUse>,
    val typeParams: List<TypeParam>,
    val superclass: ClassType?,
    val interfaces: List<ClassType>,
) {
    val ctors = mutableListOf<CtorItem>()
    val methods = mutableListOf<MethodItem>()
    val fields = mutableListOf<FieldItem>()
    val enumConstants = mutableListOf<EnumConstantItem>()

    val qualifiedName: String get() = "$pkg.$name"
    val simpleName: String get() = name.substringAfterLast('.')
    val outerName: String? get() = if ('.' in name) "$pkg.${name.substringBeforeLast('.')}" else null
    val binaryName: String get() = "${pkg.replace('.', '/')}/${name.replace('.', '$')}"

    /** A nested class without an enclosing instance (interfaces, enums and annotations always are). */
    val isStaticNested: Boolean get() = outerName != null && ("static" in modifiers || kind != ClassKind.CLASS)

    override fun toString() = qualifiedName
}

class Api(val classes: List<ClassItem>) {
    val byName: Map<String, ClassItem> = classes.associateBy { it.qualifiedName }
}

class SignatureParseException(message: String) : RuntimeException(message)

object SignatureParser {
    private const val FORMAT = "// Signature format: 2.0"

    fun parse(text: String, fileName: String): Api {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != FORMAT) {
            throw SignatureParseException("$fileName:1: expected '$FORMAT'")
        }
        val classes = mutableListOf<ClassItem>()
        var pkg: String? = null
        var current: ClassItem? = null
        for ((index, raw) in lines.withIndex().drop(1)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) continue
            val cursor = Cursor(raw, tokenize(raw, fileName, index + 1), fileName, index + 1)
            when {
                line == "}" -> if (current != null) current = null else pkg = null
                cursor.at("package") -> {
                    if (pkg != null) cursor.fail("nested package")
                    cursor.next()
                    pkg = cursor.qualifiedName()
                    cursor.expect("{")
                    cursor.end()
                }
                pkg == null -> cursor.fail("declaration outside a package")
                cursor.at("ctor") || cursor.at("method") || cursor.at("field") || cursor.at("enum_constant") ->
                    parseMember(cursor, current ?: cursor.fail("member outside a class"))
                else -> {
                    if (current != null) cursor.fail("nested class declaration")
                    current = parseClass(cursor, pkg).also(classes::add)
                }
            }
        }
        if (pkg != null || current != null) throw SignatureParseException("$fileName: unexpected end of file")
        return Api(classes)
    }

    private fun parseClass(c: Cursor, pkg: String): ClassItem {
        val annotations = c.annotations()
        val modifiers = c.modifiers()
        val kind =
            when {
                c.accept("class") -> ClassKind.CLASS
                c.accept("interface") -> ClassKind.INTERFACE
                c.accept("enum") -> ClassKind.ENUM
                c.at("@") && c.at("interface", 1) -> ClassKind.ANNOTATION.also { c.next(); c.next() }
                else -> c.fail("expected a class declaration")
            }
        val name = c.qualifiedName()
        val typeParams = c.typeParams()
        var superclass: ClassType? = null
        val interfaces = mutableListOf<ClassType>()
        if (c.accept("extends")) {
            if (kind == ClassKind.INTERFACE) {
                while (!c.at("implements") && !c.at("{")) interfaces += c.classType()
            } else {
                superclass = c.classType()
            }
        }
        if (c.accept("implements")) {
            while (!c.at("{")) interfaces += c.classType()
        }
        c.expect("{")
        c.end()
        return ClassItem(pkg, name, kind, modifiers, annotations, typeParams, superclass, interfaces)
    }

    private fun parseMember(c: Cursor, owner: ClassItem) {
        when (c.next().text) {
            "ctor" -> {
                val annotations = c.annotations()
                val modifiers = c.modifiers()
                val typeParams = c.typeParams()
                val name = c.qualifiedName()
                if (name != owner.name) c.fail("constructor $name in ${owner.name}")
                val params = c.params()
                val throws = c.throwsClause()
                c.expect(";")
                c.end()
                owner.ctors += CtorItem(modifiers, annotations, typeParams, params, throws)
            }
            "method" -> {
                val annotations = c.annotations()
                val modifiers = c.modifiers()
                val typeParams = c.typeParams()
                val returnType = c.type()
                val name = c.identifier()
                val params = c.params()
                val throws = c.throwsClause()
                val default = if (c.accept("default")) c.rawUntilSemicolon() else null
                c.expect(";")
                c.end()
                owner.methods += MethodItem(name, modifiers, annotations, typeParams, returnType, params, throws, default)
            }
            "field" -> {
                val annotations = c.annotations()
                val modifiers = c.modifiers()
                val type = c.type()
                val name = c.identifier()
                val value = if (c.accept("=")) c.rawUntilSemicolon() else null
                c.expect(";")
                c.end()
                owner.fields += FieldItem(name, modifiers, annotations, type, value)
            }
            "enum_constant" -> {
                val annotations = c.annotations()
                c.modifiers()
                c.type()
                val name = c.identifier()
                c.expect(";")
                c.end()
                owner.enumConstants += EnumConstantItem(name, annotations)
            }
        }
    }
}

private val MODIFIERS =
    setOf(
        "public", "protected", "private", "static", "final", "abstract", "default", "synchronized",
        "native", "transient", "volatile", "strictfp", "sealed",
    )

private val PRIMITIVES = setOf("boolean", "byte", "char", "short", "int", "long", "float", "double", "void")

internal class Token(val kind: Kind, val text: String, val start: Int, val end: Int) {
    enum class Kind { IDENT, NUMBER, STRING, CHAR, SYMBOL }

    override fun toString() = text
}

/** Splits one line into tokens; a `//` comment outside a literal ends the line. */
internal fun tokenize(line: String, file: String = "", lineNo: Int = 0): List<Token> {
    val out = mutableListOf<Token>()
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            ch.isWhitespace() -> i++
            line.startsWith("//", i) -> break
            ch == '"' || ch == '\'' -> {
                var j = i + 1
                while (j < line.length && line[j] != ch) j += if (line[j] == '\\') 2 else 1
                if (j >= line.length) throw SignatureParseException("$file:$lineNo: unterminated literal\n  $line")
                out += Token(if (ch == '"') Token.Kind.STRING else Token.Kind.CHAR, line.substring(i, j + 1), i, j + 1)
                i = j + 1
            }
            ch.isDigit() || (ch == '.' && line.getOrNull(i + 1)?.isDigit() == true) -> {
                val hex = line.startsWith("0x", i) || line.startsWith("0X", i)
                var j = i + 1
                while (j < line.length) {
                    val c = line[j]
                    val exponentSign = (c == '-' || c == '+') && !hex && line[j - 1] in "eE"
                    if (c.isLetterOrDigit() || c == '.' || c == '_' || exponentSign) j++ else break
                }
                out += Token(Token.Kind.NUMBER, line.substring(i, j), i, j)
                i = j
            }
            Character.isJavaIdentifierStart(ch) -> {
                var j = i + 1
                while (j < line.length && Character.isJavaIdentifierPart(line[j])) j++
                out += Token(Token.Kind.IDENT, line.substring(i, j), i, j)
                i = j
            }
            line.startsWith("...", i) -> {
                out += Token(Token.Kind.SYMBOL, "...", i, i + 3)
                i += 3
            }
            else -> {
                out += Token(Token.Kind.SYMBOL, ch.toString(), i, i + 1)
                i++
            }
        }
    }
    return out
}

private class Cursor(val line: String, val tokens: List<Token>, val file: String, val lineNo: Int) {
    var pos = 0

    fun peek(offset: Int = 0): Token? = tokens.getOrNull(pos + offset)

    fun at(text: String, offset: Int = 0): Boolean = peek(offset)?.let { it.text == text && it.kind != Token.Kind.STRING && it.kind != Token.Kind.CHAR } == true

    fun next(): Token = tokens.getOrNull(pos++) ?: fail("unexpected end of line")

    fun accept(text: String): Boolean = at(text).also { if (it) pos++ }

    fun expect(text: String): Token = next().also { if (it.text != text) fail("expected '$text' but found '${it.text}'") }

    fun end() {
        if (pos != tokens.size) fail("unexpected '${tokens[pos].text}'")
    }

    fun fail(message: String): Nothing = throw SignatureParseException("$file:$lineNo: $message\n  ${line.trim()}")

    fun identifier(): String = next().also { if (it.kind != Token.Kind.IDENT) fail("expected an identifier but found '${it.text}'") }.text

    fun qualifiedName(): String {
        val parts = mutableListOf(identifier())
        while (at(".") && peek(1)?.kind == Token.Kind.IDENT) {
            next()
            parts += identifier()
        }
        return parts.joinToString(".")
    }

    fun modifiers(): Set<String> {
        val out = linkedSetOf<String>()
        while (peek()?.kind == Token.Kind.IDENT && peek()!!.text in MODIFIERS) out += next().text
        return out
    }

    /** Annotations up to the next non-annotation token (`@interface` is a class kind, not one). */
    fun annotations(): List<AnnotationUse> {
        val out = mutableListOf<AnnotationUse>()
        while (at("@") && !at("interface", 1)) {
            next()
            val name = qualifiedName()
            var args: String? = null
            if (at("(")) {
                val open = next()
                var depth = 1
                var close: Token
                while (true) {
                    close = next()
                    if (close.kind != Token.Kind.SYMBOL) continue
                    if (close.text == "(") depth++
                    if (close.text == ")" && --depth == 0) break
                }
                args = line.substring(open.end, close.start).trim()
            }
            out += AnnotationUse(name, args)
        }
        return out
    }

    fun typeParams(): List<TypeParam> {
        if (!accept("<")) return emptyList()
        val out = mutableListOf<TypeParam>()
        do {
            annotations()
            val name = identifier()
            val bounds = mutableListOf<TypeRef>()
            if (accept("extends")) {
                do bounds += type() while (accept("&"))
            }
            out += TypeParam(name, bounds)
        } while (accept(","))
        expect(">")
        return out
    }

    fun classType(): ClassType = type() as? ClassType ?: fail("expected a class type")

    fun type(): TypeRef {
        annotations() // type-use annotations (nullness) carry no JVM-level meaning here
        if (accept("?")) {
            return when {
                accept("extends") -> WildcardType(type(), superBound = false)
                accept("super") -> WildcardType(type(), superBound = true)
                else -> WildcardType()
            }
        }
        val name = qualifiedName()
        var type: TypeRef =
            if (name in PRIMITIVES) {
                PrimitiveType(name)
            } else {
                val args = mutableListOf<TypeRef>()
                if (accept("<")) {
                    do args += type() while (accept(","))
                    expect(">")
                }
                if (at(".")) fail("member types of parameterized types are not supported")
                ClassType(name, args)
            }
        while (at("[") && at("]", 1)) {
            next()
            next()
            type = ArrayType(type)
        }
        if (accept("...")) type = ArrayType(type, varargs = true)
        return type
    }

    fun params(): List<Param> {
        expect("(")
        if (accept(")")) return emptyList()
        val out = mutableListOf<Param>()
        do {
            val annotations = annotations()
            val type = type()
            if (peek()?.kind == Token.Kind.IDENT) next() // parameter name (absent in format 2.0)
            out += Param(type, annotations)
        } while (accept(","))
        expect(")")
        return out
    }

    fun throwsClause(): List<TypeRef> {
        if (!accept("throws")) return emptyList()
        val out = mutableListOf<TypeRef>()
        do out += type() while (accept(","))
        return out
    }

    /** Raw source text from the current token up to (not including) the final `;`. */
    fun rawUntilSemicolon(): String {
        val first = peek() ?: fail("expected a value")
        val last = tokens.indexOfLast { it.kind == Token.Kind.SYMBOL && it.text == ";" }
        if (last < pos) fail("expected ';'")
        val text = line.substring(first.start, tokens[last].start).trim()
        pos = last
        return text
    }
}
