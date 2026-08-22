package cn.yzjtiantian.android.core

/**
 * 已成功加载并应用的热更新补丁。
 *
 * 补丁通过 [ModuleManager.registerPatch] 注册，业务代码可用
 * [ModuleManager.getLoadedPatches] / [ModuleManager.getPatch] 查询，
 * 例如在设置页展示、或反射调用补丁提供的新能力。
 */
data class LoadedPatch(
    val module: String,
    val version: String,
    val description: String,
    /** 补丁入口类实例（反射创建），可为 null。 */
    val instance: Any?,
    val classLoader: ClassLoader,
)

/**
 * 模块注册表：保存热更新补丁加载后的 ClassLoader 与补丁实例，
 * 供业务层在运行时查找并使用补丁提供的新类/新行为。
 */
object ModuleManager {

    private val modules = mutableMapOf<String, ClassLoader>()
    private val moduleClasses = mutableMapOf<String, Class<*>>()
    private val patches = mutableMapOf<String, LoadedPatch>()
    private val uiProviders = mutableMapOf<String, Any>()
    private val patchServices = mutableMapOf<String, Any>()

    fun registerModule(name: String, classLoader: ClassLoader) {
        modules[name] = classLoader
        android.util.Log.d("ModuleManager", "模块已注册: $name")
    }

    fun getModuleClassLoader(name: String): ClassLoader? {
        return modules[name]
    }

    fun getModuleClass(name: String, className: String): Class<*>? {
        val classLoader = modules[name] ?: return null

        return try {
            val clazz = classLoader.loadClass(className)
            moduleClasses[className] = clazz
            clazz
        } catch (e: ClassNotFoundException) {
            android.util.Log.e("ModuleManager", "类未找到: $className", e)
            null
        }
    }

    fun isModuleLoaded(name: String): Boolean {
        return modules.containsKey(name)
    }

    fun unloadModule(name: String) {
        modules.remove(name)
        android.util.Log.d("ModuleManager", "模块已卸载: $name")
    }

    /** 注册一个已加载的补丁。 */
    fun registerPatch(patch: LoadedPatch) {
        patches[patch.module] = patch
        android.util.Log.d("ModuleManager", "补丁已注册: ${patch.module} v${patch.version}")
    }

    /** 查询某模块已加载的补丁，无则返回 null。 */
    fun getPatch(module: String): LoadedPatch? = patches[module]

    /** 所有已加载的补丁。 */
    fun getLoadedPatches(): List<LoadedPatch> = patches.values.toList()

    /**
     * 注册某模块的 UI 提供者（补丁入口类在 apply() 里调用）。
     * 具体类型由基座侧约定（如 [cn.yzjtiantian.android.patchapi.ChatUiProvider]），
     * 这里仅做通用存储，避免 :core 依赖具体补丁契约。
     */
    fun registerUiProvider(module: String, provider: Any) {
        uiProviders[module] = provider
        android.util.Log.d("ModuleManager", "UI 提供者已注册: module=$module provider=${provider.javaClass.name}")
    }

    /** 查询某模块的 UI 提供者，无则返回 null。 */
    fun getUiProvider(module: String): Any? = uiProviders[module]

    /**
     * 注册一个补丁服务（补丁入口类在 apply() 里调用）。
     * 具体类型由基座侧契约约定（如 [TextSendHook]），这里仅做通用存储。
     */
    fun registerPatchService(serviceKey: String, service: Any) {
        patchServices[serviceKey] = service
        android.util.Log.d("ModuleManager", "补丁服务已注册: key=$serviceKey service=${service.javaClass.name}")
    }

    /** 查询补丁服务，无则返回 null。 */
    fun getPatchService(serviceKey: String): Any? = patchServices[serviceKey]

    /** 所有已注册的补丁服务。 */
    fun getPatchServices(): Map<String, Any> = patchServices.toMap()

    /** 所有已注册的 UI 提供者。 */
    fun getUiProviders(): Map<String, Any> = uiProviders.toMap()

    fun clearAll() {
        modules.clear()
        moduleClasses.clear()
        patches.clear()
        uiProviders.clear()
        patchServices.clear()
    }
}
