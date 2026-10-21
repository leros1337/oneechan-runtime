package oneechan.stubs

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes compilable Java stub sources for the API classes in the [include]d packages: one file per
 * top-level class, nested classes inside it. Every method and constructor body throws
 * `RuntimeException("Stub!")`; `static final` fields keep their compile-time constant values, so
 * javac emits the same ConstantValue attributes compilers inline. Other API classes (java.*) only
 * serve to resolve supertypes, constructors and exceptions.
 */
class JavaStubWriter(
    private val api: Api,
    private val include: List<String>,
    /** Emit `@NonNull`/`@Nullable` as `android.annotation.NonNull/Nullable` (class retention), as android.jar does. */
    private val nullness: Boolean = true,
    /** Declaration order of enum constants by enum name (see [EnumOrder]); others keep the file's order. */
    private val enumOrder: Map<String, List<String>> = emptyMap(),
) {
    class Result(val files: List<String>, val classes: Int, val warnings: List<String>)

    private val warnings = mutableListOf<String>()
    private val children: Map<String, List<ClassItem>> = api.classes.filter { it.outerName != null }.groupBy { it.outerName!! }
    private val subclasses: Map<String, List<ClassItem>> =
        api.classes
            .mapNotNull { c -> c.superclass?.let { sup -> resolve(sup.name, scope(c))?.let { it.qualifiedName to c } } }
            .groupBy({ it.first }, { it.second })

    fun isIncluded(c: ClassItem): Boolean = include.any { c.pkg == it || c.pkg.startsWith("$it.") }

    fun write(out: Path): Result {
        val files = mutableListOf<String>()
        var classes = 0
        for (c in api.classes) {
            if (c.outerName != null || !isIncluded(c)) continue
            val source = StringBuilder()
            source.append(HEADER).append("package ").append(c.pkg).append(";\n\n")
            classes += source.writeClass(c, "")
            files += writeFile(out, c.pkg, c.simpleName, source.toString())
        }
        if (nullness) {
            for (name in listOf("NonNull", "Nullable")) {
                if (api.byName["android.annotation.$name"] != null) continue
                // Compile-only (not part of the published API): android.jar marks nullness with these
                // class-retention annotations, which Kotlin reads as nullability information.
                val text = "${HEADER}package android.annotation;\n\n" +
                    "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)\npublic @interface $name {\n}\n"
                files += writeFile(out, "android.annotation", name, text)
            }
        }
        return Result(files, classes, warnings.toList())
    }

    private fun writeFile(out: Path, pkg: String, name: String, text: String): String {
        val relative = "${pkg.replace('.', '/')}/$name.java"
        val file = out.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return relative
    }

    // --- Names and types -----------------------------------------------------------------------

    /** Type variables visible inside [c]: its own and, for inner (non-static) classes, the enclosing ones. */
    fun scope(c: ClassItem): Set<String> {
        val own = c.typeParams.map { it.name }.toSet()
        val outer = c.outerName?.let { api.byName[it] }
        return if (outer != null && !c.isStaticNested) own + scope(outer) else own
    }

    /** Fully qualifies a name as written: simple and capitalized-dotted names are java.lang types. */
    fun qualify(name: String, scope: Set<String>): String =
        when {
            '.' !in name -> if (name in scope) name else "java.lang.$name"
            name[0].isUpperCase() -> "java.lang.$name"
            else -> name
        }

    private val javaLang = HashMap<String, Boolean>()

    /** A java.lang class in the API, or else in the running JDK. */
    private fun isJavaLang(simpleName: String): Boolean =
        javaLang.getOrPut(simpleName) {
            "java.lang.$simpleName" in api.byName ||
                runCatching { Class.forName("java.lang.$simpleName", false, ClassLoader.getPlatformClassLoader()) }.isSuccess
        }

    private fun resolve(name: String, scope: Set<String>): ClassItem? = if ('.' !in name && name in scope) null else api.byName[qualify(name, scope)]

    fun render(type: TypeRef, scope: Set<String>, varargs: Boolean = false): String =
        when (type) {
            is PrimitiveType -> type.name
            is ArrayType -> render(type.component, scope) + if (type.varargs && varargs) "..." else "[]"
            is WildcardType ->
                when {
                    type.bound == null -> "?"
                    type.superBound -> "? super " + render(type.bound, scope)
                    else -> "? extends " + render(type.bound, scope)
                }
            is ClassType -> {
                val name = qualify(type.name, scope)
                if ('.' !in type.name && type.name !in scope && !isJavaLang(type.name)) warnings += "unknown simple name ${type.name}"
                name + if (type.args.isEmpty()) "" else type.args.joinToString(",", "<", ">") { render(it, scope) }
            }
        }

    /**
     * Type variables a member uses without declaring them. Format 2.0 drops the type parameters of
     * generic constructors (`<T> OutputConfiguration(Size, Class<T>)` is written without `<T>`):
     * a simple name that is neither in scope nor a java.lang class is such a variable.
     */
    private fun freeTypeVariables(types: List<TypeRef>, scope: Set<String>): List<TypeParam> {
        val names = linkedSetOf<String>()
        fun visit(type: TypeRef?) {
            when (type) {
                is ClassType -> {
                    if ('.' !in type.name && type.name !in scope && !isJavaLang(type.name)) names += type.name
                    type.args.forEach(::visit)
                }
                is ArrayType -> visit(type.component)
                is WildcardType -> visit(type.bound)
                is PrimitiveType, null -> {}
            }
        }
        types.forEach(::visit)
        return names.map { TypeParam(it, emptyList()) }
    }

    private fun renderTypeParams(params: List<TypeParam>, scope: Set<String>): String =
        if (params.isEmpty()) {
            ""
        } else {
            params.joinToString(", ", "<", ">") { p ->
                p.name + if (p.bounds.isEmpty()) "" else " extends " + p.bounds.joinToString(" & ") { render(it, scope) }
            }
        }

    private fun annotationName(a: AnnotationUse): String? =
        when {
            a.name == "Deprecated" -> "java.lang.Deprecated"
            a.name == "NonNull" && nullness -> "android.annotation.NonNull"
            a.name == "Nullable" && nullness -> "android.annotation.Nullable"
            // Other simple names are hidden android.annotation.* types (@IntRange, @RequiresPermission...).
            '.' !in a.name -> null
            a.name.startsWith("java.lang.") -> a.name
            api.byName[a.name]?.kind == ClassKind.ANNOTATION -> a.name
            else -> null
        }

    private fun renderAnnotations(list: List<AnnotationUse>): String =
        list.mapNotNull { a -> annotationName(a)?.let { "@$it" + (a.args?.let { args -> "($args)" } ?: "") + " " } }.joinToString("")

    // --- Classes -------------------------------------------------------------------------------

    /** Appends [c] and its nested classes; returns the number of classes written. */
    private fun StringBuilder.writeClass(c: ClassItem, indent: String): Int {
        val scope = scope(c)
        val modifiers =
            c.modifiers.filter { m ->
                when {
                    c.outerName == null && m == "static" -> false
                    c.kind == ClassKind.ENUM && (m == "final" || m == "abstract") -> false
                    else -> true
                }
            }
        append(indent).append(renderAnnotations(c.annotations))
        modifiers.forEach { append(it).append(' ') }
        append(c.kind.keyword).append(' ').append(c.simpleName).append(renderTypeParams(c.typeParams, scope))
        val supertypes = c.interfaces.joinToString(", ") { render(it, scope) }
        when (c.kind) {
            ClassKind.CLASS -> {
                c.superclass?.takeIf { qualify(it.name, scope) != "java.lang.Object" }?.let { append(" extends ").append(render(it, scope)) }
                if (supertypes.isNotEmpty()) append(" implements ").append(supertypes)
            }
            ClassKind.INTERFACE -> if (supertypes.isNotEmpty()) append(" extends ").append(supertypes)
            ClassKind.ENUM -> if (supertypes.isNotEmpty()) append(" implements ").append(supertypes)
            ClassKind.ANNOTATION -> {}
        }
        append(" {\n")
        val inner = "$indent  "

        if (c.kind == ClassKind.ENUM) {
            append(inner)
            append(enumConstants(c).joinToString(", ") { renderAnnotations(it.annotations) + it.name })
            append(";\n")
        }

        val staticInit = mutableListOf<FieldItem>()
        for (f in c.fields) {
            val implicitConstant = c.kind == ClassKind.INTERFACE || c.kind == ClassKind.ANNOTATION
            val isStatic = "static" in f.modifiers || implicitConstant
            val isFinal = "final" in f.modifiers || implicitConstant
            val initializer =
                when {
                    f.value != null && isFinal -> " = " + constant(f, c)
                    isStatic && isFinal && implicitConstant -> " = " + nonConstant(f.type)
                    isStatic && isFinal -> {
                        staticInit += f
                        ""
                    }
                    else -> ""
                }
            append(inner).append(renderAnnotations(f.annotations))
            f.modifiers.forEach { append(it).append(' ') }
            append(render(f.type, scope)).append(' ').append(f.name).append(initializer).append(";\n")
        }
        if (staticInit.isNotEmpty()) {
            // Assigned in an initializer, not a declaration, so javac does not make them constants.
            append(inner).append("static {\n")
            staticInit.forEach { append(inner).append("  ").append(it.name).append(" = ").append(defaultValue(it.type)).append(";\n") }
            append(inner).append("}\n")
        }

        if (c.kind == ClassKind.CLASS) {
            val superCall = superCall(c)
            for (declared in constructors(c)) {
                val ctor = declared.copy(typeParams = declared.typeParams + freeTypeVariables(declared.params.map { it.type } + declared.throws, scope + declared.typeParams.map { it.name }))
                val ctorScope = scope + ctor.typeParams.map { it.name }
                val throws = (ctor.throws.map { render(it, ctorScope) } + superCall.throws).distinct()
                append(inner).append(renderAnnotations(ctor.annotations))
                ctor.modifiers.forEach { append(it).append(' ') }
                if (ctor.typeParams.isNotEmpty()) append(renderTypeParams(ctor.typeParams, ctorScope)).append(' ')
                append(c.simpleName).append('(').append(renderParams(ctor.params, ctorScope)).append(')')
                if (throws.isNotEmpty()) append(" throws ").append(throws.joinToString(", "))
                append(" {")
                superCall.text?.let { append(' ').append(it) }
                append(" throw new java.lang.RuntimeException(\"Stub!\"); }\n")
            }
        }

        for (m in declaredMethods(c) + elidedOverrides(c)) {
            val methodScope = scope + m.typeParams.map { it.name }
            val abstract =
                c.kind == ClassKind.ANNOTATION || "abstract" in m.modifiers || "native" in m.modifiers ||
                    (c.kind == ClassKind.INTERFACE && "default" !in m.modifiers && "static" !in m.modifiers)
            append(inner).append(renderAnnotations(m.annotations))
            m.modifiers.forEach { append(it).append(' ') }
            if (m.typeParams.isNotEmpty()) append(renderTypeParams(m.typeParams, methodScope)).append(' ')
            append(render(m.returnType, methodScope)).append(' ').append(m.name)
            append('(').append(renderParams(m.params, methodScope)).append(')')
            if (m.throws.isNotEmpty()) append(" throws ").append(m.throws.joinToString(", ") { render(it, methodScope) })
            when {
                c.kind == ClassKind.ANNOTATION -> {
                    m.defaultValue?.let { append(" default ").append(it) }
                    append(";\n")
                }
                abstract -> append(";\n")
                else -> append(" { throw new java.lang.RuntimeException(\"Stub!\"); }\n")
            }
        }

        var count = 1
        for (nested in children[c.qualifiedName].orEmpty()) {
            append('\n')
            count += writeClass(nested, inner)
        }
        append(indent).append("}\n")
        return count
    }

    /** The API methods of [c], with type parameters the signature file dropped restored. */
    private fun declaredMethods(c: ClassItem): List<MethodItem> {
        val scope = scope(c)
        return c.methods.filterNot { c.kind == ClassKind.ENUM && isImplicitEnumMethod(it, scope) }.map { m ->
            val free = freeTypeVariables(m.params.map { it.type } + m.returnType + m.throws, scope + m.typeParams.map { it.name })
            if (free.isEmpty()) m else m.copy(typeParams = m.typeParams + free)
        }
    }

    private fun enumConstants(c: ClassItem): List<EnumConstantItem> {
        val order = enumOrder[c.qualifiedName] ?: return c.enumConstants
        val byName = c.enumConstants.associateBy { it.name }
        if (order.toSet() != byName.keys) {
            warnings += "${c.qualifiedName}: enum order ${order.sorted()} does not match constants ${byName.keys.sorted()}"
            return c.enumConstants
        }
        return order.map(byName::getValue)
    }

    private fun renderParams(params: List<Param>, scope: Set<String>): String =
        params.mapIndexed { i, p -> renderAnnotations(p.annotations) + render(p.type, scope, varargs = i == params.lastIndex) + " p$i" }.joinToString(", ")

    private fun isImplicitEnumMethod(m: MethodItem, scope: Set<String>): Boolean =
        (m.name == "values" && m.params.isEmpty() && "static" in m.modifiers) ||
            (m.name == "valueOf" && m.params.size == 1 && "static" in m.modifiers && render(m.params[0].type, scope) == "java.lang.String")

    // --- Fields --------------------------------------------------------------------------------

    private fun constant(f: FieldItem, owner: ClassItem): String {
        val raw = f.value!!
        val type = (f.type as? PrimitiveType)?.name ?: qualify((f.type as? ClassType)?.name ?: "", scope(owner))
        return when (type) {
            "java.lang.String" -> {
                if (!raw.startsWith('"') || !raw.endsWith('"')) warnings += "${owner.qualifiedName}.${f.name}: not a string literal: $raw"
                javaStringLiteral(unescapeJava(raw.substring(1, raw.length - 1)))
            }
            "char" -> if (raw.startsWith('\'')) raw else "(char) $raw"
            else -> raw
        }
    }

    private fun defaultValue(type: TypeRef): String =
        when ((type as? PrimitiveType)?.name) {
            null -> "null"
            "boolean" -> "false"
            "long" -> "0L"
            "float" -> "0.0f"
            "double" -> "0.0d"
            "int" -> "0"
            else -> "(${type.name}) 0"
        }

    /** An initializer javac does not treat as a compile-time constant (interface fields need one). */
    private fun nonConstant(type: TypeRef): String =
        when (val name = (type as? PrimitiveType)?.name) {
            null -> "null"
            "boolean" -> "java.lang.Boolean.valueOf(false).booleanValue()"
            else -> "($name) java.lang.Integer.valueOf(0).intValue()"
        }

    // --- Constructors --------------------------------------------------------------------------

    /** API constructors, or one hidden no-argument constructor (as metalava emits) when there are none. */
    private fun constructors(c: ClassItem): List<CtorItem> =
        c.ctors.ifEmpty {
            val subs = subclasses[c.qualifiedName].orEmpty()
            val visibility =
                when {
                    subs.isEmpty() -> setOf("private")
                    subs.any { it.pkg != c.pkg } -> setOf("protected")
                    else -> emptySet()
                }
            listOf(CtorItem(visibility, emptyList(), emptyList(), emptyList(), emptyList()))
        }

    private class SuperCall(val text: String?, val throws: List<String>)

    /**
     * The explicit `super(...)` call [c]'s constructors need: the accessible superclass constructor
     * declaring the fewest checked exceptions and parameters, called with typed null/zero arguments
     * so the overload is unambiguous. Its checked exceptions are added to the caller's throws.
     */
    private fun superCall(c: ClassItem): SuperCall {
        val sup = c.superclass ?: return SuperCall(null, emptyList())
        val scope = scope(c)
        val supClass = resolve(sup.name, scope)
        if (supClass == null) {
            warnings += "${c.qualifiedName}: unknown superclass ${sup.name}"
            return SuperCall(null, emptyList())
        }
        if (supClass.qualifiedName == "java.lang.Object") return SuperCall(null, emptyList())
        val candidates = constructors(supClass).filter { accessible(it, supClass, c) }
        if (candidates.isEmpty()) {
            warnings += "${c.qualifiedName}: no accessible constructor in ${supClass.qualifiedName}"
            return SuperCall(null, emptyList())
        }
        val supScope = scope(supClass)
        val best = candidates.minWith(compareBy({ checked(it.throws, supScope).size }, { it.params.size }))
        val throws = checked(best.throws, supScope).map { render(it, supScope) }
        if (best.params.isEmpty()) return SuperCall(null, throws)
        val substitution = if (sup.args.isEmpty()) null else supClass.typeParams.map { it.name }.zip(sup.args).toMap()
        val ctorVars = best.typeParams.associateBy { it.name }
        val args =
            best.params.joinToString(", ") { p ->
                when (val t = p.type) {
                    is PrimitiveType -> if (t.name == "boolean") "false" else "(${t.name}) 0"
                    else -> "(" + substitute(t, supClass, ctorVars, substitution, scope) + ") null"
                }
            }
        return SuperCall("super($args);", throws)
    }

    /**
     * Renders a superclass constructor parameter type in the subclass: the superclass's type
     * variables become the subclass's type arguments, or erasures for a raw supertype.
     */
    private fun substitute(type: TypeRef, owner: ClassItem, ctorVars: Map<String, TypeParam>, substitution: Map<String, TypeRef>?, target: Set<String>): String {
        val ownerScope = scope(owner)
        return when (type) {
            is PrimitiveType -> type.name
            is ArrayType -> substitute(type.component, owner, ctorVars, substitution, target) + "[]"
            is WildcardType ->
                when {
                    type.bound == null -> "?"
                    type.superBound -> "? super " + substitute(type.bound, owner, ctorVars, substitution, target)
                    else -> "? extends " + substitute(type.bound, owner, ctorVars, substitution, target)
                }
            is ClassType ->
                if ('.' !in type.name && (type.name in ownerScope || type.name in ctorVars)) {
                    val variable = ctorVars[type.name] ?: owner.typeParams.firstOrNull { it.name == type.name }
                    substitution?.get(type.name)?.takeIf { type.name !in ctorVars }?.let { render(it, target) }
                        ?: erasure(variable?.bounds?.firstOrNull(), ownerScope)
                } else {
                    val name = qualify(type.name, ownerScope)
                    if (substitution == null || type.args.isEmpty()) {
                        name
                    } else {
                        name + type.args.joinToString(",", "<", ">") { substitute(it, owner, ctorVars, substitution, target) }
                    }
                }
        }
    }

    private fun erasure(type: TypeRef?, scope: Set<String>): String =
        when (type) {
            null -> "java.lang.Object"
            is ClassType -> if ('.' !in type.name && type.name in scope) "java.lang.Object" else qualify(type.name, scope)
            is ArrayType -> erasure(type.component, scope) + "[]"
            is PrimitiveType -> type.name
            is WildcardType -> "java.lang.Object"
        }

    private fun accessible(ctor: CtorItem, owner: ClassItem, from: ClassItem): Boolean =
        when {
            "public" in ctor.modifiers || "protected" in ctor.modifiers -> true
            // Private members are accessible within the same top-level class.
            "private" in ctor.modifiers -> owner.pkg == from.pkg && owner.name.substringBefore('.') == from.name.substringBefore('.')
            else -> owner.pkg == from.pkg
        }

    private fun checked(throws: List<TypeRef>, scope: Set<String>): List<TypeRef> = throws.filter { isChecked(it, scope) }

    private fun isChecked(type: TypeRef, scope: Set<String>): Boolean {
        if (type !is ClassType || ('.' !in type.name && type.name in scope)) return true
        var cls = resolve(type.name, scope) ?: return true
        while (true) {
            when (cls.qualifiedName) {
                "java.lang.RuntimeException", "java.lang.Error" -> return false
                "java.lang.Throwable", "java.lang.Exception" -> return true
            }
            val sup = cls.superclass ?: return true
            cls = resolve(sup.name, scope(cls)) ?: return true
        }
    }

    // --- Elided overrides ------------------------------------------------------------------------

    // Signature files leave out a method that overrides an inherited one when nothing visible
    // changes, even when it implements an abstract method (CellSignalStrengthGsm.hashCode() for the
    // abstract CellSignalStrength.hashCode(), GridLayout.onLayout for ViewGroup's) or resolves a
    // default/abstract conflict between superinterfaces (XmlResourceParser.getAttributeNamespace).
    // Such methods are restored so the stubs compile, as they are declared in the real classes.

    /** An inherited instance method, its types rewritten into the inheriting class's scope. */
    private class Inherited(val owner: ClassItem, val method: MethodItem, val key: String) {
        val isDefault: Boolean get() = "default" in method.modifiers
        val isAbstract: Boolean get() = "abstract" in method.modifiers || (owner.kind == ClassKind.INTERFACE && !isDefault)
    }

    private fun elidedOverrides(c: ClassItem): List<MethodItem> {
        if (c.kind == ClassKind.ANNOTATION) return emptyList()
        val declared = declaredMethods(c).filter { "static" !in it.modifiers }.map { key(it, c) }.toSet()
        val (chain, interfaces) = inheritedMethods(c)
        val nearestInClass = LinkedHashMap<String, Inherited>()
        chain.forEach { nearestInClass.putIfAbsent(it.key, it) }
        val fromInterfaces = interfaces.groupBy { it.key }
        val concrete = c.kind == ClassKind.ENUM || (c.kind == ClassKind.CLASS && "abstract" !in c.modifiers)
        // Members of a deprecated class are deprecated too (metalava marks them so).
        val deprecated = if (c.annotations.any { it.name == "Deprecated" }) listOf(AnnotationUse("Deprecated")) else emptyList()
        val out = mutableListOf<MethodItem>()
        for (key in (nearestInClass.keys + fromInterfaces.keys).distinct()) {
            if (key in declared) continue
            val inClass = nearestInClass[key]
            if (inClass != null && !inClass.isAbstract) continue // a superclass implements it
            val candidates = mostSpecific(fromInterfaces[key].orEmpty())
            val defaults = candidates.filter { it.isDefault }
            val conflict = defaults.isNotEmpty() && candidates.size > 1
            when {
                concrete -> {
                    // A single most specific default implements it, unless a superclass re-abstracts it.
                    if (inClass == null && candidates.size == 1 && defaults.size == 1) continue
                    val source = inClass ?: candidates.firstOrNull { it.isAbstract } ?: candidates.first()
                    val visibility = if ("protected" in source.method.modifiers) "protected" else "public"
                    out += source.method.copy(modifiers = setOf(visibility), annotations = deprecated + source.method.annotations, defaultValue = null)
                }
                conflict && inClass == null ->
                    out += candidates.first().method.copy(
                        modifiers = if (c.kind == ClassKind.INTERFACE) setOf("public") else setOf("public", "abstract"),
                        annotations = deprecated + candidates.first().method.annotations,
                        defaultValue = null,
                    )
            }
        }
        return out
    }

    /** Drops declarations overridden by another candidate's interface (it extends theirs). */
    private fun mostSpecific(candidates: List<Inherited>): List<Inherited> =
        candidates.filter { a -> candidates.none { b -> b.owner !== a.owner && isSubinterface(b.owner, a.owner) } }

    private fun isSubinterface(sub: ClassItem, sup: ClassItem): Boolean =
        sub.interfaces.any { ref -> resolve(ref.name, scope(sub))?.let { it === sup || isSubinterface(it, sup) } == true }

    /** Instance methods of the superclass chain (nearest first) and of every superinterface. */
    private fun inheritedMethods(c: ClassItem): Pair<List<Inherited>, List<Inherited>> {
        val chain = mutableListOf<Inherited>()
        val interfaces = mutableListOf<Inherited>()
        val seen = HashSet<String>()

        fun members(owner: ClassItem, substitution: Map<String, TypeRef>?, into: MutableList<Inherited>) {
            for (m in declaredMethods(owner)) {
                if ("static" in m.modifiers || "private" in m.modifiers) continue
                val rewritten = rewrite(m, owner, substitution)
                into += Inherited(owner, rewritten, key(rewritten, c))
            }
        }

        fun visitInterfaces(type: ClassItem, substitution: Map<String, TypeRef>?) {
            for (ref in type.interfaces) {
                val (sup, supSubstitution) = supertype(type, substitution, ref) ?: continue
                if (!seen.add(sup.qualifiedName)) continue
                members(sup, supSubstitution, interfaces)
                visitInterfaces(sup, supSubstitution)
            }
        }

        val identity: Map<String, TypeRef> = typeParamsInScope(c).associate { it.name to ClassType(it.name) }
        visitInterfaces(c, identity)
        var type = c
        var substitution: Map<String, TypeRef>? = identity
        while (type.kind == ClassKind.CLASS || type.kind == ClassKind.ENUM) {
            val ref =
                type.superclass
                    ?: when {
                        type.kind == ClassKind.ENUM -> ClassType("java.lang.Enum", listOf(ClassType(type.qualifiedName)))
                        type.qualifiedName != "java.lang.Object" -> ClassType("java.lang.Object")
                        else -> null
                    }
                    ?: break
            val (sup, supSubstitution) = supertype(type, substitution, ref) ?: break
            members(sup, supSubstitution, chain)
            visitInterfaces(sup, supSubstitution)
            type = sup
            substitution = supSubstitution
        }
        return chain to interfaces
    }

    /** The supertype [ref] of [type], with its type variables mapped into the inheriting class (null: raw). */
    private fun supertype(type: ClassItem, substitution: Map<String, TypeRef>?, ref: ClassType): Pair<ClassItem, Map<String, TypeRef>?>? {
        val sup = resolve(ref.name, scope(type)) ?: return null
        val supSubstitution =
            when {
                substitution == null -> null
                ref.args.isEmpty() -> if (sup.typeParams.isEmpty()) emptyMap() else null
                else -> sup.typeParams.map { it.name }.zip(ref.args.map { rewriteType(it, type, substitution, emptySet()) }).toMap()
            }
        return sup to supSubstitution
    }

    /** [m] of [owner] as seen from a subclass: substituted types, nullness annotations only. */
    private fun rewrite(m: MethodItem, owner: ClassItem, substitution: Map<String, TypeRef>?): MethodItem {
        val vars = m.typeParams.map { it.name }.toSet()
        fun t(type: TypeRef) = rewriteType(type, owner, substitution, vars)
        val nullness = { a: AnnotationUse -> a.name == "NonNull" || a.name == "Nullable" }
        return m.copy(
            annotations = m.annotations.filter(nullness),
            typeParams = m.typeParams.map { p -> TypeParam(p.name, p.bounds.map(::t)) },
            returnType = t(m.returnType),
            params = m.params.map { p -> Param(t(p.type), p.annotations.filter(nullness)) },
            throws = m.throws.map(::t),
        )
    }

    /**
     * Rewrites a type written in [owner] for use in a subclass: owner's type variables become the
     * subclass's type arguments (erasures when inherited raw), other simple names get java.lang.
     */
    private fun rewriteType(type: TypeRef, owner: ClassItem, substitution: Map<String, TypeRef>?, methodVars: Set<String>): TypeRef =
        when (type) {
            is PrimitiveType -> type
            is ArrayType -> ArrayType(rewriteType(type.component, owner, substitution, methodVars), type.varargs)
            is WildcardType -> WildcardType(type.bound?.let { rewriteType(it, owner, substitution, methodVars) }, type.superBound)
            is ClassType -> {
                val ownerScope = scope(owner)
                when {
                    '.' !in type.name && type.name in methodVars -> ClassType(type.name)
                    '.' !in type.name && type.name in ownerScope ->
                        substitution?.get(type.name) ?: ClassType(erasure(typeParamsInScope(owner).firstOrNull { it.name == type.name }?.bounds?.firstOrNull(), ownerScope))
                    else -> ClassType(qualify(type.name, ownerScope), if (substitution == null) emptyList() else type.args.map { rewriteType(it, owner, substitution, methodVars) })
                }
            }
        }

    private fun typeParamsInScope(c: ClassItem): List<TypeParam> {
        val outer = c.outerName?.let { api.byName[it] }
        return if (outer != null && !c.isStaticNested) c.typeParams + typeParamsInScope(outer) else c.typeParams
    }

    /** Override identity: name and erased parameter types, seen from [target]. */
    private fun key(m: MethodItem, target: ClassItem): String {
        val bounds = typeParamsInScope(target).associate { it.name to it.bounds.firstOrNull() } + m.typeParams.associate { it.name to it.bounds.firstOrNull() }
        return m.name + m.params.joinToString(",", "(", ")") { erasureKey(it.type, bounds) }
    }

    private fun erasureKey(type: TypeRef, bounds: Map<String, TypeRef?>): String =
        when (type) {
            is PrimitiveType -> type.name
            is ArrayType -> erasureKey(type.component, bounds) + "[]"
            is WildcardType -> "java.lang.Object"
            is ClassType ->
                if ('.' !in type.name && type.name in bounds) {
                    bounds[type.name]?.let { erasureKey(it, bounds - type.name) } ?: "java.lang.Object"
                } else {
                    qualify(type.name, emptySet())
                }
        }

    companion object {
        const val HEADER ="// Generated from the Android API signature (AOSP frameworks/base api/current.txt) by\n" +
            "// extension-host/android-stubs. Do not edit.\n"
    }
}

/** Decodes the escapes of a Java string literal body. */
fun unescapeJava(body: String): String {
    val out = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '\\') {
            out.append(c)
            i++
            continue
        }
        val e = body[i + 1]
        i += 2
        when (e) {
            'n' -> out.append('\n')
            't' -> out.append('\t')
            'b' -> out.append('\b')
            'r' -> out.append('\r')
            'f' -> out.append('\u000c')
            's' -> out.append(' ')
            '"', '\'', '\\' -> out.append(e)
            'u' -> {
                while (body[i] == 'u') i++
                out.append(body.substring(i, i + 4).toInt(16).toChar())
                i += 4
            }
            in '0'..'7' -> {
                var end = i
                val max = if (e <= '3') 2 else 1
                while (end < body.length && end - i < max && body[end] in '0'..'7') end++
                out.append((e.toString() + body.substring(i, end)).toInt(8).toChar())
                i = end
            }
            else -> throw IllegalArgumentException("bad escape \\$e in \"$body\"")
        }
    }
    return out.toString()
}

/**
 * A Java string literal for [value] that stays ASCII and never relies on `\u` escapes for line
 * breaks, quotes or backslashes (javac decodes `\u` escapes before it tokenizes).
 */
fun javaStringLiteral(value: String): String =
    buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c == '\u000c' -> append("\\f")
                c.code < 0x20 || c.code == 0x7f -> append("\\").append(c.code.toString(8).padStart(3, '0'))
                c.code > 0x7e -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }
