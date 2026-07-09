package com.simple.auto.register.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import java.io.File

/**
 * KSP Symbol Processor cho annotation [@AutoRegister].
 */
class AutoRegisterProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    /**
     * Thông tin chi tiết về một symbol đã được trích xuất.
     * Lưu trữ dưới dạng các object bất biến (ClassName, String) để tương thích với KSP2,
     * nơi các symbol AST bị invalid sau mỗi round.
     */
    private data class SymbolInfo(
        val implClassName: ClassName,
        val apiClassNames: List<ClassName>,
        val packageName: String,
        val simpleName: String,
        val qualifiedName: String,
        val filePath: String
    )

    // ─── Trạng thái qua các round ────────────────────────────────────────────

    private val accumulatedSymbols = mutableMapOf<String, SymbolInfo>()
    
    /** 
     * Lưu trữ các KSFile từ round hiện tại. Trong KSP2, KSFile chỉ hợp lệ trong round nó được tạo.
     * Vì chúng ta generate file trong finish(), chúng ta chỉ có thể tham chiếu đến các file 
     * của round cuối cùng hoặc chấp nhận không truyền dependencies (aggregating=true vẫn cần file).
     * Tuy nhiên, với aggregating processor, KSP2 khuyến khích trích xuất dữ liệu sớm.
     */
    private val currentRoundFiles = mutableSetOf<KSFile>()

    private var hasAnySymbol: Boolean = false

    // ─── Hằng số ─────────────────────────────────────────────────────────────

    companion object {
        private const val BASE_PKG = "com.simple.auto.register"
        private val AUTO_REGISTER_ANNOTATION = ClassName(BASE_PKG, "AutoRegister")
        private val MANAGER_CLASS = ClassName(BASE_PKG, "AutoRegisterManager")
        private val INITIALIZER_INTERFACE = ClassName(BASE_PKG, "ModuleInitializer")
        private val KEEP_ANNOTATION = ClassName("androidx.annotation", "Keep")

        private const val MODULE_TYPE_APP     = "app"
        private const val MODULE_TYPE_LIBRARY = "library"
        private const val MODULE_TYPE_DYNAMIC = "dynamic"
    }

    // ─── Entry point ──────────────────────────────────────────────────────────

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val roundSymbols = collectAnnotatedSymbols(resolver)
        
        roundSymbols.forEach { symbol ->
            val info = extractSymbolInfo(symbol)
            if (info != null) {
                if (accumulatedSymbols.put(info.qualifiedName, info) == null) {
                    hasAnySymbol = true
                }
                symbol.containingFile?.let { currentRoundFiles.add(it) }
            }
        }

        return emptyList()
    }

    override fun finish() {
        if (!hasAnySymbol || accumulatedSymbols.isEmpty()) return

        try {
            val allSymbols = accumulatedSymbols.values.toList()
            val moduleInfo = resolveModuleInfo(allSymbols)

            generateLoaderClass(
                pkg       = moduleInfo.generatedPackage,
                className = moduleInfo.generatedClassName,
                symbols   = allSymbols
            )

            if (!moduleInfo.isDynamic) {
                generateSpiServiceFile(
                    pkg       = moduleInfo.generatedPackage,
                    className = moduleInfo.generatedClassName
                )
            }
        } catch (e: Exception) {
            logger.error("AutoRegister: Error generating loader in finish(): ${e.message}\n${e.stackTraceToString()}")
        }
    }

    // ─── Trích xuất dữ liệu ──────────────────────────────────────────────────

    private fun extractSymbolInfo(symbol: KSClassDeclaration): SymbolInfo? {
        val qualifiedName = symbol.qualifiedName?.asString() ?: symbol.simpleName.asString()
        val packageName = symbol.packageName.asString()
        val simpleName = symbol.simpleName.asString()
        val filePath = symbol.containingFile?.filePath ?: ""
        val implClassName = symbol.toClassName()

        val annotation = symbol.annotations.firstOrNull { ann ->
            ann.annotationType.resolve().declaration.qualifiedName?.asString() ==
                AUTO_REGISTER_ANNOTATION.canonicalName
        } ?: return null

        val apiTypes = annotation.arguments
            .firstOrNull { it.name?.asString() == "apis" }
            ?.value as? List<*>
            ?: emptyList<Any>()

        val apiClassNames = apiTypes
            .filterIsInstance<KSType>()
            .mapNotNull { (it.declaration as? KSClassDeclaration)?.toClassName() }

        return SymbolInfo(
            implClassName = implClassName,
            apiClassNames = apiClassNames,
            packageName = packageName,
            simpleName = simpleName,
            qualifiedName = qualifiedName,
            filePath = filePath
        )
    }

    private fun collectAnnotatedSymbols(resolver: Resolver): List<KSClassDeclaration> {
        return resolver
            .getSymbolsWithAnnotation(AUTO_REGISTER_ANNOTATION.canonicalName)
            .filterIsInstance<KSClassDeclaration>()
            .filter { symbol ->
                !isGeneratedLoader(symbol) && !implementsModuleInitializer(symbol)
            }
            .toList()
    }

    private fun isGeneratedLoader(symbol: KSClassDeclaration): Boolean {
        val name = symbol.simpleName.asString()
        return name.endsWith("Loader")
            || name.endsWith("LibraryLoader")
            || name.endsWith("DynamicFeatureLoader")
    }

    private fun implementsModuleInitializer(symbol: KSClassDeclaration): Boolean {
        return symbol.superTypes.any { superType ->
            superType.resolve().declaration.qualifiedName?.asString() == INITIALIZER_INTERFACE.canonicalName
        }
    }

    // ─── Metadata module ─────────────────────────────────────────────────────

    private data class ModuleMetadata(
        val moduleType: String,
        val isDynamic: Boolean,
        val generatedPackage: String,
        val generatedClassName: String
    )

    private fun resolveModuleInfo(allSymbols: List<SymbolInfo>): ModuleMetadata {
        val firstFilePath = allSymbols.first().filePath
        val gradleContent = findAndReadGradleFile(firstFilePath)

        val forcedType = options["moduleType"]?.lowercase()

        val isDynamic = forcedType == MODULE_TYPE_DYNAMIC
            || (forcedType == null && gradleContent.containsAny("com.android.dynamic-feature", "dynamic-feature"))

        val isApp = forcedType == MODULE_TYPE_APP
            || (forcedType == null && !isDynamic && gradleContent.containsAny("com.android.application", "applicationId"))

        val moduleType = when {
            isDynamic -> MODULE_TYPE_DYNAMIC
            isApp     -> MODULE_TYPE_APP
            else      -> MODULE_TYPE_LIBRARY
        }

        val moduleName = options["moduleName"] ?: extractModuleName(firstFilePath)
        val generatedPackage = resolveGeneratedPackage(isDynamic, moduleName, allSymbols)
        val generatedClassName = resolveGeneratedClassName(isDynamic, isApp, moduleName)

        return ModuleMetadata(
            moduleType       = moduleType,
            isDynamic        = isDynamic,
            generatedPackage = generatedPackage,
            generatedClassName = generatedClassName
        )
    }

    private fun resolveGeneratedPackage(
        isDynamic: Boolean,
        moduleName: String,
        symbols: List<SymbolInfo>
    ): String {
        return if (isDynamic) {
            "$BASE_PKG.generated.${moduleName.lowercase().replace("-", "_")}"
        } else {
            findCommonPackage(symbols.map { it.packageName })
        }
    }

    private fun resolveGeneratedClassName(
        isDynamic: Boolean,
        isApp: Boolean,
        moduleName: String
    ): String {
        val suffix = when {
            isDynamic -> "DynamicFeatureLoader"
            isApp     -> "Loader"
            else      -> "LibraryLoader"
        }
        val pascal = moduleName
            .split("-", "_")
            .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
        return pascal + suffix
    }

    // ─── Sinh file Kotlin Loader ──────────────────────────────────────────────

    private fun generateLoaderClass(pkg: String, className: String, symbols: List<SymbolInfo>) {
        val createFun = FunSpec.builder("create")
            .addModifiers(KModifier.OVERRIDE)

        symbols.forEach { info ->
            info.apiClassNames.forEach { apiClass ->
                createFun.addStatement(
                    "%T.register(%T::class.java.name, %T::class.java.name)",
                    MANAGER_CLASS, apiClass, info.implClassName
                )
            }
        }

        val loaderClass = TypeSpec.classBuilder(className)
            .addAnnotation(KEEP_ANNOTATION)
            .addSuperinterface(INITIALIZER_INTERFACE)
            .addFunction(createFun.build())
            .build()

        val fileSpec = FileSpec.builder(pkg, className)
            .addType(loaderClass)
            .build()

        // Trong KSP2, nếu finish() được gọi, chúng ta có thể không có KSFile hợp lệ từ các round trước.
        // Tuy nhiên aggregating=true vẫn cần thiết. Chúng ta sử dụng các file từ round cuối cùng nếu có.
        val dependencies = Dependencies(aggregating = true, *currentRoundFiles.toTypedArray())
        fileSpec.writeTo(codeGenerator, dependencies)
    }

    private fun generateSpiServiceFile(pkg: String, className: String) {
        val servicePath = "META-INF/services/${INITIALIZER_INTERFACE.canonicalName}"
        val fullClassName = "$pkg.$className"
        try {
            codeGenerator
                .createNewFile(Dependencies(false), "", servicePath, "")
                .writer()
                .use { writer -> writer.write(fullClassName) }
        } catch (e: Exception) {
            // Ignore
        }
    }

    // ─── Tiện ích ────────────────────────────────────────────────────────────

    private fun findAndReadGradleFile(startPath: String): String {
        if (startPath.isEmpty()) return ""
        var dir = File(startPath).parentFile
        while (dir != null) {
            val gradle = File(dir, "build.gradle").takeIf { it.exists() }
                ?: File(dir, "build.gradle.kts").takeIf { it.exists() }
            if (gradle != null) return gradle.readText()
            dir = dir.parentFile
        }
        return ""
    }

    private fun extractModuleName(path: String): String {
        val parts = path.replace("\\", "/").split("/")
        val srcIndex = parts.indexOf("src")
        if (srcIndex > 0) return parts[srcIndex - 1]
        val buildIndex = parts.indexOf("build")
        if (buildIndex > 0) return parts[buildIndex - 1]
        return "GeneratedModule"
    }

    private fun findCommonPackage(packages: List<String>): String {
        if (packages.isEmpty()) return "$BASE_PKG.generated"
        val splitPackages = packages.map { it.split(".") }
        val shortest = splitPackages.minByOrNull { it.size } ?: return "$BASE_PKG.generated"
        val commonParts = shortest.indices
            .takeWhile { i -> splitPackages.all { it.size > i && it[i] == shortest[i] } }
            .map { i -> shortest[i] }
        return if (commonParts.isEmpty()) "$BASE_PKG.generated"
        else commonParts.joinToString(".")
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }
}
