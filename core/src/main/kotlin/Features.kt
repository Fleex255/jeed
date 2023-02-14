package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass

enum class FeatureName(val description: String) {
    LOCAL_VARIABLE_DECLARATIONS("local variable declarations"),
    VARIABLE_ASSIGNMENTS("variable assignments"),
    VARIABLE_REASSIGNMENTS("variable reassignments"),

    // Operators
    UNARY_OPERATORS("unary operators"),
    ARITHMETIC_OPERATORS("arithmetic operators"),
    BITWISE_OPERATORS("bitwise operators"),
    ASSIGNMENT_OPERATORS("assignment operators"),
    TERNARY_OPERATOR("ternary operators"),
    COMPARISON_OPERATORS("comparison operators"),
    LOGICAL_OPERATORS("logical operators"),
    PRIMITIVE_CASTING("primitive casting"),

    // If & Else
    IF_STATEMENTS("if statements"),
    ELSE_STATEMENTS("else statements"),
    ELSE_IF("else-if blocks"),

    // Arrays
    ARRAYS("arrays"),
    ARRAY_ACCESS("array accesses"),
    ARRAY_LITERAL("array literal"),
    MULTIDIMENSIONAL_ARRAYS("multidimensional arrays"),

    // Loops
    FOR_LOOPS("for loops"),
    ENHANCED_FOR("enhanced for loops"),
    WHILE_LOOPS("while loops"),
    DO_WHILE_LOOPS("do-while loops"),
    BREAK("break"),
    CONTINUE("continue"),

    // Nesting
    NESTED_IF("nested if"),
    NESTED_FOR("nested for"),
    NESTED_WHILE("nested while"),
    NESTED_DO_WHILE("nested do-while"),
    NESTED_CLASS("nested class declaration"),
    NESTED_LOOP("nested loop"),

    // Methods
    METHOD("method declarations"),
    RETURN("method return"),
    CONSTRUCTOR("constructor declarations"),
    GETTER("getters"),
    SETTER("setters"),

    // Strings & null
    STRING("Strings"),
    NULL("null"),

    // Type handling
    CASTING("type casting"),
    TYPE_INFERENCE("type inference"),
    INSTANCEOF("instanceof"),

    // Class & Interface
    CLASS("class declarations"),
    IMPLEMENTS("interface implementations"),
    INTERFACE("interface declarations"),

    // Polymorphism
    EXTENDS("inheritance"),
    SUPER("super"),
    OVERRIDE("overrides"),

    // Exceptions
    TRY_BLOCK("try statement"),
    FINALLY("finally block"),
    ASSERT("assert"),
    THROW("throw"),
    THROWS("throws"),

    // Objects
    NEW_KEYWORD("new keyword"),
    THIS("this"),
    REFERENCE_EQUALITY("referential equality"),
    CLASS_FIELD("class field"),
    EQUALITY("equals"),

    // Modifiers
    VISIBILITY_MODIFIERS("visibility modifiers"),
    STATIC_METHOD("static methods"),
    FINAL_METHOD("final methods"),
    ABSTRACT_METHOD("abstract methods"),
    STATIC_FIELD("static fields"),
    FINAL_FIELD("final fields"),
    ABSTRACT_FIELD("abstract fields"),
    FINAL_CLASS("final classes"),
    ABSTRACT_CLASS("abstract classes"),

    // Import
    IMPORT("import statements"),

    // Misc.
    ANONYMOUS_CLASSES("anonymous classes"),
    LAMBDA_EXPRESSIONS("lambda expressions"),
    GENERIC_CLASS("generic classes"),
    SWITCH("switch statements"),
    STREAM("streams"),
    ENUM("enums"),

    COMPARABLE("Comparable interface"),
    RECORD("records"),
    BOXING_CLASSES("boxing classes"),
    TYPE_PARAMETERS("type parameters"),
    PRINT_STATEMENTS("print statements"),

    // Dot
    DOT_NOTATION("dot notation"),
    DOTTED_METHOD_CALL("dotted method call"),
    DOTTED_VARIABLE_ACCESS("dotted variable access"),

    // Kotlin only
    NESTED_METHOD("nested method"),
    JAVA_PRINT_STATEMENTS("java print statements"),
    REQUIRE_OR_CHECK("require or check"),
    FOR_LOOP_STEP("for loop step"),
    ELVIS_OPERATOR("elvis operator"),
    FOR_LOOP_RANGE("for loop range"),
    SECONDARY_CONSTRUCTOR("secondary constructor"),
    JAVA_EQUALITY("java equals"),
    COMPANION_OBJECT("companion object"),
    NULLABLE_TYPE("nullable type"),
    WHEN("when statement"),
    EXPLICIT_TYPE("explicit type"),
    DATA_CLASS("data class"),
    OPEN_CLASS("open class"),
    OPEN_METHOD("open method")
}

@Suppress("unused")
val MISSING_KOTLIN_FEATURES = setOf(
    // FeatureName.TERNARY_OPERATOR (no equivalent)

    // Arrays
    FeatureName.ARRAY_ACCESS,
    FeatureName.ARRAY_LITERAL,
    FeatureName.MULTIDIMENSIONAL_ARRAYS,

    // Loops
    // FeatureName.ENHANCED_FOR (no equivalent)

    // FeatureName.THROWS (no equivalent)

    // Objects
    // FeatureName.NEW_KEYWORD (no equivalent)

    // Modifiers
    FeatureName.STATIC_METHOD,
    // FeatureName.FINAL_METHOD (default, no equivalent),
    FeatureName.STATIC_FIELD,
    FeatureName.FINAL_FIELD,
    FeatureName.ABSTRACT_FIELD,
    // FeatureName.FINAL_CLASS (default, no equivalent)

    // Misc.
    FeatureName.ANONYMOUS_CLASSES,
    FeatureName.LAMBDA_EXPRESSIONS
    // FeatureName.SWITCH (no equivalent)
    // FeatureName.STREAM (no equivalent, can't detect due to inference)

    // FeatureName.RECORD (Kotlin FeatureName.DATA_CLASS)
    // FeatureName.BOXING_CLASSES (no equivalent)
)

val ALL_FEATURES = FeatureName.values().associate { it.name to it.description }

class FeatureMap(val map: MutableMap<FeatureName, Int> = mutableMapOf()) : MutableMap<FeatureName, Int> by map {
    override fun get(key: FeatureName): Int = map.getOrDefault(key, 0)
    override fun put(key: FeatureName, value: Int): Int? {
        val previous = map[key]
        if (value == 0) {
            map.remove(key)
        } else {
            map[key] = value
        }
        return previous
    }
}

@JsonClass(generateAdapter = true)
data class LocatedFeature(val feature: FeatureName, val location: Location)

fun List<LocatedFeature>.toLineMap(): Map<Int, List<LocatedFeature>> {
    val lines = map { it.location.line }.distinct()
    val map = mutableMapOf<Int, List<LocatedFeature>>()
    for (line in lines) {
        map[line] = filter { it.location.line == line }.sortedBy { it.location.column }
    }
    return map
}

@JsonClass(generateAdapter = true)
data class Features(
    val featureMap: FeatureMap = FeatureMap(),
    val featureList: MutableList<LocatedFeature> = mutableListOf(),
    val importList: MutableSet<String> = mutableSetOf(),
    val typeList: MutableSet<String> = mutableSetOf(),
    val identifierList: MutableSet<String> = mutableSetOf(),
    val dottedMethodList: MutableSet<String> = mutableSetOf()
) {
    operator fun plus(other: Features): Features {
        val map = FeatureMap()
        for (key in FeatureName.values()) {
            map[key] = featureMap.getValue(key) + other.featureMap.getValue(key)
        }
        return Features(
            map,
            (featureList + other.featureList).toMutableList(),
            (importList + other.importList).toMutableSet(),
            (typeList + other.typeList).toMutableSet(),
            (identifierList + other.identifierList).toMutableSet(),
            (dottedMethodList + other.dottedMethodList).toMutableSet()
        )
    }
}

sealed class FeatureValue(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    var features: Features
) : LocatedClassOrMethod(name, range, methods, classes) {
    fun lookup(name: String): FeatureValue {
        check(name.isNotEmpty())
        return if (name[0].isUpperCase() && !name.endsWith(")")) {
            classes[name] ?: error("class $name not found ${classes.keys}")
        } else {
            methods[name] ?: error("method $name not found ${methods.keys}")
        } as FeatureValue
    }

    fun finalize(): FeatureValue {
        features.featureList.sortWith(compareBy({ it.location.line }, { it.location.column }))
        return this
    }
}

@JsonClass(generateAdapter = true)
class ClassFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    features: Features = Features()
) : FeatureValue(name, range, methods, classes, features)

@JsonClass(generateAdapter = true)
class MethodFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    features: Features = Features()
) : FeatureValue(name, range, methods, classes, features)

@JsonClass(generateAdapter = true)
class UnitFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    features: Features = Features()
) : FeatureValue(name, range, methods, classes, features)

class FeaturesFailed(errors: List<SourceError>) : JeedError(errors) {
    override fun toString(): String {
        return "errors were encountered while performing feature analysis: ${errors.joinToString(separator = ",")}"
    }
}

class FeaturesResults(val source: Source, val results: Map<String, Map<String, UnitFeatures>>) {
    @Suppress("ReturnCount")
    fun lookup(path: String, filename: String = ""): FeatureValue {
        val components = path.split(".").toMutableList()

        if (source is Snippet) {
            require(filename == "") { "filename cannot be set for snippet lookups" }
        }
        val resultSource = results[filename] ?: error("results does not contain key $filename")

        val unitFeatures = resultSource[filename] ?: error("missing UnitFeatures")
        if (path == "") {
            return unitFeatures
        }

        var currentFeatures = if (source is Snippet) {
            val rootFeatures = unitFeatures.classes[""] as? FeatureValue ?: error("")
            if (path.isEmpty()) {
                return rootFeatures
            } else if (path == ".") {
                return rootFeatures.methods[""] as FeatureValue
            }
            if (unitFeatures.classes[components[0]] != null) {
                unitFeatures.classes[components.removeAt(0)]
            } else {
                rootFeatures
            }
        } else {
            val component = components.removeAt(0)
            unitFeatures.classes[component].also {
                check(it != null) {
                    "$component was null in feature lookup path"
                }
            }
        } as FeatureValue

        for (component in components) {
            currentFeatures = currentFeatures.lookup(component)
        }
        return currentFeatures
    }
}

@Throws(FeaturesFailed::class)
fun Source.features(names: Set<String> = sources.keys.toSet()): FeaturesResults {
    @Suppress("SwallowedException")
    try {
        return FeaturesResults(
            this,
            sources.filter {
                names.contains(it.key)
            }.mapValues {
                when (type) {
                    Source.FileType.JAVA -> JavaFeatureListener(this, it).results
                    Source.FileType.KOTLIN -> KotlinFeatureListener(this, it).results
                }
            }
        )
    } catch (e: JeedParsingException) {
        throw FeaturesFailed(e.errors)
    }
}
