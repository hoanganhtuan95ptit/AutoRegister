package com.simple.auto.register.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
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
 *
 * ## Cách hoạt động
 * Processor này chạy trong nhiều round của KSP. Mỗi round, nó:
 *  1. Thu thập tất cả class được đánh dấu [@AutoRegister] (kể cả class được sinh ra bởi KSP khác).
 *  2. Tích lũy các class đó vào [accumulatedSymbols] theo thời gian.
 *  3. Mỗi khi xuất hiện class mới, nó generate (hoặc ghi đè) một file Loader duy nhất
 *     chứa toàn bộ danh sách đã tích lũy.
 *
 * ## Lý do tích lũy qua nhiều round
 * [@AutoRegister] có `@Retention(SOURCE)`, nên KSP chỉ đọc được annotation từ source file.
 * Một số class (ví dụ [HandlerRegisterImpl]) được sinh ra bởi KSP khác ở round 1 và chỉ
 * hiển thị với processor này ở round 2 trở đi. Nếu chỉ chạy một lần ở round 1 sẽ bỏ sót
 * những class đó.
 *
 * ## Cách ghi file
 * - **Round đầu tiên**: dùng [CodeGenerator] của KSP để tạo file (đúng chuẩn KSP).
 * - **Round tiếp theo**: [CodeGenerator] không cho phép ghi lại file đã tồn tại, nên
 *   processor tự tìm file trên disk và ghi đè trực tiếp bằng [File.writeText].
 *
 * @param codeGenerator KSP code generator để tạo file Kotlin.
 * @param logger        KSP logger để in thông tin debug/error.
 * @param options       Các tùy chọn cấu hình được truyền từ build.gradle (moduleType, moduleName…).
 */
class AutoRegisterProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    // ─── Trạng thái qua các round ────────────────────────────────────────────

    /**
     * Danh sách tích lũy tất cả class [@AutoRegister] đã thấy qua mọi round,
     * keyed bởi qualified name để tránh trùng lặp.
     *
     * Sử dụng Map thay vì Set để dễ tra cứu và replace khi cần.
     */
    private val accumulatedSymbols = mutableMapOf<String, KSClassDeclaration>()

    /**
     * Cờ đánh dấu đã có symbol mới được tích lũy (cần generate file).
     * Được đặt thành `true` mỗi khi có symbol mới, và được dùng trong [finish]
     * để quyết định có cần generate file hay không.
     */
    private var hasAnySymbol: Boolean = false

    // ─── Hằng số ─────────────────────────────────────────────────────────────

    companion object {
        /** Package gốc của thư viện AutoRegister. */
        private const val BASE_PKG = "com.simple.auto.register"

        /** ClassName của annotation [@AutoRegister], dùng để tìm symbol được đánh dấu. */
        private val AUTO_REGISTER_ANNOTATION = ClassName(BASE_PKG, "AutoRegister")

        /** ClassName của [AutoRegisterManager], được tham chiếu trong code sinh ra. */
        private val MANAGER_CLASS = ClassName(BASE_PKG, "AutoRegisterManager")

        /** ClassName của interface [ModuleInitializer] mà Loader sẽ implement. */
        private val INITIALIZER_INTERFACE = ClassName(BASE_PKG, "ModuleInitializer")

        // Các giá trị hợp lệ của option "moduleType" trong build.gradle:
        // ksp { arg("moduleType", "app") }  hoặc "library" hoặc "dynamic"
        private const val MODULE_TYPE_APP     = "app"
        private const val MODULE_TYPE_LIBRARY = "library"
        private const val MODULE_TYPE_DYNAMIC = "dynamic"
    }

    // ─── Entry point ──────────────────────────────────────────────────────────

    /**
     * Được KSP gọi mỗi round. Trả về danh sách symbol bị defer sang round sau
     * (ở đây luôn trả về rỗng vì chúng ta tự xử lý multi-round qua [accumulatedSymbols]).
     */
    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.warn("AutoRegister [process] ▶ round bắt đầu (accumulated=${accumulatedSymbols.size})")

        // Bước 1: Lấy tất cả class @AutoRegister trong round này, lọc bỏ các class nội bộ.
        val roundSymbols = collectAnnotatedSymbols(resolver)
        logger.warn("AutoRegister [process] bước 1 – roundSymbols=${roundSymbols.size}: ${roundSymbols.map { it.qualifiedName?.asString() }}")
        if (roundSymbols.isEmpty()) {
            logger.warn("AutoRegister [process] ◀ không có symbol mới, bỏ qua round này")
            return emptyList()
        }

        // Bước 2: Tích lũy vào map tổng.
        val hasNewSymbols = mergeIntoAccumulated(roundSymbols)
        logger.warn("AutoRegister [process] bước 2 – hasNewSymbols=$hasNewSymbols, accumulated=${accumulatedSymbols.size}")
        if (hasNewSymbols) hasAnySymbol = true

        // Không generate file ở đây — chờ finish() để có đầy đủ symbol từ tất cả round.
        logger.warn("AutoRegister [process] ◀ round hoàn tất (file sẽ được generate trong finish())")
        return emptyList()
    }

    /**
     * Được KSP gọi sau khi tất cả các round xử lý đã hoàn tất.
     *
     * Đây là nơi duy nhất chúng ta generate file Loader. Lý do:
     * - Nếu generate trong [process] ở round 1, package được tính từ tập symbol chưa đầy đủ
     *   (một số class do KSP khác sinh ra chỉ xuất hiện ở round 2 trở đi).
     * - Kết quả là file được tạo ra ở đường dẫn sai (package sai), và các round sau
     *   chỉ có thể ghi đè nội dung chứ không thể di chuyển file sang đúng thư mục.
     * - Bằng cách defer toàn bộ việc generate sang [finish], chúng ta đảm bảo package
     *   luôn được tính từ tập symbol đầy đủ → file luôn ở đúng vị trí.
     */
    override fun finish() {
        logger.warn("AutoRegister [finish] ▶ bắt đầu (accumulated=${accumulatedSymbols.size}, hasAnySymbol=$hasAnySymbol)")
        if (!hasAnySymbol || accumulatedSymbols.isEmpty()) {
            logger.warn("AutoRegister [finish] ◀ không có symbol nào, bỏ qua generate")
            return
        }
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

            logger.info(
                "AutoRegister: Generated ${moduleInfo.generatedPackage}.${moduleInfo.generatedClassName}" +
                " with ${allSymbols.size} impl(s) (type=${moduleInfo.moduleType})"
            )
        } catch (e: Exception) {
            logger.error("AutoRegister: Error generating loader in finish(): ${e.message}")
        }
        logger.warn("AutoRegister [finish] ◀ hoàn tất")
    }

    // ─── Bước 1: Thu thập symbol ──────────────────────────────────────────────

    /**
     * Lấy tất cả [KSClassDeclaration] được đánh dấu [@AutoRegister] trong round hiện tại,
     * loại bỏ các class nội bộ của thư viện để tránh vòng lặp vô hạn.
     *
     * Cụ thể, loại bỏ:
     *  - Các class Loader do chính processor này sinh ra (tên kết thúc bằng "Loader",
     *    "LibraryLoader", hoặc "DynamicFeatureLoader").
     *  - Các class implement [ModuleInitializer] (luôn là Loader nội bộ).
     */
    private fun collectAnnotatedSymbols(resolver: Resolver): List<KSClassDeclaration> {
        val all = resolver
            .getSymbolsWithAnnotation(AUTO_REGISTER_ANNOTATION.canonicalName)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        logger.warn("AutoRegister [collectAnnotatedSymbols] tổng tìm được=${all.size}: ${all.map { it.qualifiedName?.asString() }}")

        val filtered = all.filter { symbol ->
            val keep = isExternalSymbol(symbol)
            if (!keep) {
                logger.warn("AutoRegister [collectAnnotatedSymbols] ✗ lọc bỏ (internal): ${symbol.qualifiedName?.asString()}")
            }
            keep
        }

        logger.warn("AutoRegister [collectAnnotatedSymbols] sau lọc=${filtered.size}: ${filtered.map { it.qualifiedName?.asString() }}")
        return filtered
    }

    /**
     * Trả về `true` nếu [symbol] là class người dùng tự viết (hoặc KSP khác sinh ra),
     * không phải class Loader nội bộ của AutoRegister.
     */
    private fun isExternalSymbol(symbol: KSClassDeclaration): Boolean {
        return !isGeneratedLoader(symbol) && !implementsModuleInitializer(symbol)
    }

    /**
     * Trả về `true` nếu tên class là một Loader do AutoRegister tự sinh ra.
     * Mục đích: ngăn processor đọc lại file nó vừa tạo và tạo đệ quy vô hạn.
     */
    private fun isGeneratedLoader(symbol: KSClassDeclaration): Boolean {
        val name = symbol.simpleName.asString()
        val result = name.endsWith("Loader")
            || name.endsWith("LibraryLoader")
            || name.endsWith("DynamicFeatureLoader")
        if (result) logger.warn("AutoRegister [isGeneratedLoader] '$name' là loader nội bộ → bỏ qua")
        return result
    }

    /**
     * Trả về `true` nếu class implement [ModuleInitializer].
     * Các Loader nội bộ luôn implement interface này nên dùng để lọc thêm lần nữa.
     */
    private fun implementsModuleInitializer(symbol: KSClassDeclaration): Boolean {
        val result = symbol.superTypes.any { superType ->
            superType.resolve().declaration.qualifiedName?.asString() == INITIALIZER_INTERFACE.canonicalName
        }
        if (result) logger.warn("AutoRegister [implementsModuleInitializer] '${symbol.simpleName.asString()}' implement ModuleInitializer → bỏ qua")
        return result
    }

    // ─── Bước 2: Tích lũy symbol ─────────────────────────────────────────────

    /**
     * Thêm các [roundSymbols] vào [accumulatedSymbols].
     *
     * @return `true` nếu có ít nhất một symbol mới được thêm vào (map lớn hơn trước),
     *         `false` nếu tất cả đã tồn tại (không cần generate lại).
     */
    private fun mergeIntoAccumulated(roundSymbols: List<KSClassDeclaration>): Boolean {
        var hasNew = false
        roundSymbols.forEach { symbol ->
            val key = symbol.qualifiedName?.asString() ?: symbol.simpleName.asString()
            val isNew = accumulatedSymbols.put(key, symbol) == null
            if (isNew) {
                logger.warn("AutoRegister [mergeIntoAccumulated] ✚ symbol mới: $key")
            } else {
                logger.warn("AutoRegister [mergeIntoAccumulated] ~ đã có: $key")
            }
            if (isNew) hasNew = true
        }
        logger.warn("AutoRegister [mergeIntoAccumulated] hasNew=$hasNew, accumulated total=${accumulatedSymbols.size}")
        return hasNew
    }

    // ─── Metadata module ─────────────────────────────────────────────────────

    /**
     * Data class chứa toàn bộ thông tin đã được resolve về module hiện tại.
     *
     * @param moduleType       Loại module: "app", "library", hoặc "dynamic".
     * @param isDynamic        `true` nếu module là dynamic feature.
     * @param generatedPackage Package của file Loader sẽ được tạo.
     * @param generatedClassName Tên class Loader sẽ được tạo.
     */
    private data class ModuleInfo(
        val moduleType: String,
        val isDynamic: Boolean,
        val generatedPackage: String,
        val generatedClassName: String
    )

    /**
     * Đọc build.gradle của module và options KSP để xác định:
     * - Loại module (app / library / dynamic feature).
     * - Tên module (dùng để đặt tên class Loader).
     * - Package và tên class cho file Loader sẽ được tạo.
     *
     * Thứ tự ưu tiên: option KSP ("moduleType", "moduleName") > tự suy từ build.gradle > fallback.
     */
    private fun resolveModuleInfo(allSymbols: List<KSClassDeclaration>): ModuleInfo {
        val firstFilePath = allSymbols.first().containingFile?.filePath ?: ""
        logger.warn("AutoRegister [resolveModuleInfo] firstFilePath=$firstFilePath")

        val gradleContent = findAndReadGradleFile(firstFilePath)
        logger.warn("AutoRegister [resolveModuleInfo] gradle tìm thấy=${gradleContent.isNotEmpty()}, length=${gradleContent.length}")

        // Cho phép override thủ công qua KSP option để xử lý edge case.
        val forcedType = options["moduleType"]?.lowercase()
        logger.warn("AutoRegister [resolveModuleInfo] KSP options=$options, forcedType=$forcedType")

        val isDynamic = forcedType == MODULE_TYPE_DYNAMIC
            || (forcedType == null && gradleContent.containsAny("com.android.dynamic-feature", "dynamic-feature"))

        val isApp = forcedType == MODULE_TYPE_APP
            || (forcedType == null && !isDynamic && gradleContent.containsAny("com.android.application", "applicationId"))

        val moduleType = when {
            isDynamic -> MODULE_TYPE_DYNAMIC
            isApp     -> MODULE_TYPE_APP
            else      -> MODULE_TYPE_LIBRARY
        }
        logger.warn("AutoRegister [resolveModuleInfo] isDynamic=$isDynamic, isApp=$isApp, moduleType=$moduleType")

        val moduleName = options["moduleName"] ?: extractModuleName(firstFilePath)
        logger.warn("AutoRegister [resolveModuleInfo] moduleName=$moduleName")

        val generatedPackage = resolveGeneratedPackage(isDynamic, moduleName, allSymbols)
        val generatedClassName = resolveGeneratedClassName(isDynamic, isApp, moduleName)
        logger.warn("AutoRegister [resolveModuleInfo] generatedPackage=$generatedPackage, generatedClassName=$generatedClassName")

        return ModuleInfo(
            moduleType       = moduleType,
            isDynamic        = isDynamic,
            generatedPackage = generatedPackage,
            generatedClassName = generatedClassName
        )
    }

    /**
     * Tính package cho file Loader được generate.
     *
     * - Dynamic feature: dùng package cố định theo tên module
     *   (vì dynamic feature load qua reflection, cần package xác định).
     * - App / Library: dùng package chung nhất (longest common prefix) của tất cả symbol,
     *   giúp Loader nằm gần với code người dùng.
     */
    private fun resolveGeneratedPackage(
        isDynamic: Boolean,
        moduleName: String,
        symbols: List<KSClassDeclaration>
    ): String {
        return if (isDynamic) {
            // Ví dụ: moduleName = "my-feature" → "com.simple.auto.register.generated.my_feature"
            "$BASE_PKG.generated.${moduleName.lowercase().replace("-", "_")}"
        } else {
            findCommonPackage(symbols.map { it.packageName.asString() })
        }
    }

    /**
     * Tính tên class cho file Loader được generate.
     *
     * Quy tắc đặt tên: PascalCase của moduleName + suffix theo loại module.
     * Ví dụ: "my-deeplink" → "MyDeeplinkLibraryLoader"
     *
     * Suffix:
     * - Dynamic feature → "DynamicFeatureLoader"
     * - App            → "Loader"
     * - Library        → "LibraryLoader"
     */
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

    /**
     * Sinh file `[className].kt` chứa class Loader qua [CodeGenerator] của KSP.
     *
     * Hàm này chỉ được gọi một lần duy nhất từ [finish], sau khi tất cả symbol
     * đã được tích lũy đầy đủ qua mọi round. Nhờ đó, [pkg] luôn được tính đúng
     * và file được tạo ở đúng thư mục ngay từ đầu.
     */
    private fun generateLoaderClass(pkg: String, className: String, symbols: List<KSClassDeclaration>) {
        logger.warn("AutoRegister [generateLoaderClass] pkg=$pkg, className=$className, symbols=${symbols.size}")
        val fileSpec = buildLoaderFileSpec(pkg, className, symbols)
        val dependencies = Dependencies(
            aggregating = true,
            *symbols.mapNotNull { it.containingFile }.toTypedArray()
        )
        fileSpec.writeTo(codeGenerator, dependencies)
        logger.warn("AutoRegister [generateLoaderClass] ✓ file đã được ghi qua codeGenerator")
    }

    // ─── Sinh nội dung FileSpec ───────────────────────────────────────────────

    /**
     * Xây dựng [FileSpec] (KotlinPoet) cho class Loader với toàn bộ [symbols].
     *
     * File được generate có dạng:
     * ```kotlin
     * package com.example
     *
     * class DeeplinkLibraryLoader : ModuleInitializer {
     *     override fun create() {
     *         AutoRegisterManager.register(DeeplinkRegister::class.java.name, HandlerRegisterImpl::class.java.name)
     *         AutoRegisterManager.register(DeeplinkRegister::class.java.name, ModuleNameDeeplinkRegister::class.java.name)
     *         // …
     *     }
     * }
     * ```
     */
    private fun buildLoaderFileSpec(
        pkg: String,
        className: String,
        symbols: List<KSClassDeclaration>
    ): FileSpec {
        val createFun = buildCreateFunction(symbols)

        val loaderClass = TypeSpec.classBuilder(className)
            .addSuperinterface(INITIALIZER_INTERFACE)
            .addFunction(createFun)
            .build()

        return FileSpec.builder(pkg, className)
            .addType(loaderClass)
            .build()
    }

    /**
     * Sinh hàm `override fun create()` chứa các lời gọi [AutoRegisterManager.register].
     *
     * Với mỗi [KSClassDeclaration] trong [symbols], đọc giá trị `apis` từ annotation
     * [@AutoRegister] rồi emit một dòng register cho mỗi API được khai báo.
     *
     * Ví dụ với `@AutoRegister(apis = [DeeplinkRegister::class])`:
     * ```kotlin
     * AutoRegisterManager.register(
     *     "com.example.DeeplinkRegister",
     *     "com.example.HandlerRegisterImpl"
     * )
     * ```
     */
    private fun buildCreateFunction(symbols: List<KSClassDeclaration>): FunSpec {
        val funBuilder = FunSpec.builder("create")
            .addModifiers(KModifier.OVERRIDE)

        symbols.forEach { implClass ->
            val registerStatements = buildRegisterStatements(implClass)
            registerStatements.forEach { (apiClass, implClassName) ->
                funBuilder.addStatement(
                    "%T.register(%T::class.java.name, %T::class.java.name)",
                    MANAGER_CLASS, apiClass, implClassName
                )
            }
        }

        return funBuilder.build()
    }

    /**
     * Đọc annotation [@AutoRegister] trên [implClass] và trả về danh sách cặp
     * (apiClassName, implClassName) cần được register.
     *
     * Một class có thể register với nhiều API cùng lúc:
     * ```kotlin
     * @AutoRegister(apis = [ApiA::class, ApiB::class])
     * class MyImpl : ApiA, ApiB
     * ```
     * → trả về [(ApiA, MyImpl), (ApiB, MyImpl)]
     */
    private fun buildRegisterStatements(implClass: KSClassDeclaration): List<Pair<ClassName, ClassName>> {
        val implName = implClass.qualifiedName?.asString() ?: implClass.simpleName.asString()
        logger.warn("AutoRegister [buildRegisterStatements] xử lý implClass=$implName")

        val annotation = implClass.annotations.firstOrNull { ann ->
            ann.annotationType.resolve().declaration.qualifiedName?.asString() ==
                AUTO_REGISTER_ANNOTATION.canonicalName
        } ?: run {
            logger.warn("AutoRegister [buildRegisterStatements] ✗ không tìm thấy @AutoRegister trên $implName")
            return emptyList()
        }

        // Giá trị của tham số "apis" là List<KSType>
        val apiTypes = annotation.arguments
            .firstOrNull { it.name?.asString() == "apis" }
            ?.value as? List<*>
            ?: run {
            logger.warn("AutoRegister [buildRegisterStatements] ✗ không đọc được tham số 'apis' từ $implName")
            return emptyList()
        }

        val implClassName = implClass.toClassName()
        logger.warn("AutoRegister [buildRegisterStatements] implClassName=$implClassName, số api=${apiTypes.size}")

        return apiTypes
            .filterIsInstance<KSType>()
            .map { apiType ->
                val apiClass = (apiType.declaration as KSClassDeclaration).toClassName()
                logger.warn("AutoRegister [buildRegisterStatements] ✓ register($apiClass, $implClassName)")
                apiClass to implClassName
            }
    }

    // ─── Sinh SPI service file ────────────────────────────────────────────────

    /**
     * Tạo file `META-INF/services/com.simple.auto.register.ModuleInitializer` để đăng ký
     * Loader với Java [java.util.ServiceLoader].
     *
     * Nhờ cơ chế SPI, [AutoRegisterManager.loadModules] có thể tự động phát hiện tất cả
     * Loader trên classpath mà không cần hard-code tên từng class.
     *
     * Exception được bỏ qua vì file này có thể đã tồn tại từ round trước.
     */
    private fun generateSpiServiceFile(pkg: String, className: String) {
        val servicePath = "META-INF/services/${INITIALIZER_INTERFACE.canonicalName}"
        val fullClassName = "$pkg.$className"
        logger.warn("AutoRegister [generateSpiServiceFile] servicePath=$servicePath, entry=$fullClassName")
        try {
            codeGenerator
                .createNewFile(Dependencies(false), "", servicePath, "")
                .writer()
                .use { writer -> writer.write(fullClassName) }
            logger.warn("AutoRegister [generateSpiServiceFile] ✓ ghi SPI file thành công")
        } catch (e: Exception) {
            // File đã tồn tại từ round trước → bỏ qua, không cần ghi lại.
            logger.warn("AutoRegister [generateSpiServiceFile] ~ SPI file đã tồn tại, bỏ qua (${e.message})")
        }
    }

    // ─── Tiện ích đọc Gradle & đường dẫn ─────────────────────────────────────

    /**
     * Tìm và đọc nội dung file `build.gradle` hoặc `build.gradle.kts` gần nhất
     * từ [startPath] đi ngược lên thư mục cha.
     *
     * Dừng lại khi tìm thấy file Gradle đầu tiên (thường là của module hiện tại).
     * Trả về chuỗi rỗng nếu không tìm thấy (tránh crash).
     */
    private fun findAndReadGradleFile(startPath: String): String {
        var dir = File(startPath).parentFile
        while (dir != null) {
            val gradle = File(dir, "build.gradle").takeIf { it.exists() }
                ?: File(dir, "build.gradle.kts").takeIf { it.exists() }
            if (gradle != null) return gradle.readText()
            dir = dir.parentFile
        }
        return ""
    }

    /**
     * Trích xuất tên module từ đường dẫn file source.
     *
     * Chiến lược (theo thứ tự ưu tiên):
     *  1. Tìm thư mục ngay trước `src/` → đây là tên module chuẩn.
     *  2. Tìm thư mục ngay trước `build/` → dùng cho file do KSP khác generate.
     *  3. Fallback về "GeneratedModule" nếu không xác định được.
     *
     * Ví dụ: `.../deeplink/src/main/kotlin/…` → "deeplink"
     */
    private fun extractModuleName(path: String): String {
        val parts = path.replace("\\", "/").split("/")

        val srcIndex = parts.indexOf("src")
        if (srcIndex > 0) return parts[srcIndex - 1]

        val buildIndex = parts.indexOf("build")
        if (buildIndex > 0) return parts[buildIndex - 1]

        return "GeneratedModule"
    }

    /**
     * Tìm package chung dài nhất (longest common prefix) từ danh sách [packages].
     *
     * Dùng để đặt package cho Loader sao cho nằm gần nhất với code người dùng.
     *
     * Ví dụ:
     * - ["com.example.feature.a", "com.example.feature.b"] → "com.example.feature"
     * - ["com.a", "org.b"] → "$BASE_PKG.generated" (không có điểm chung)
     */
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

    // ─── Extension ────────────────────────────────────────────────────────────

    /** Trả về `true` nếu chuỗi chứa ít nhất một trong các [keywords]. */
    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }
}
