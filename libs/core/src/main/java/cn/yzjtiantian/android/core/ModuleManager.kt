package cn.yzjtiantian.android.core

import java.lang.reflect.Method

object ModuleManager {

    private val modules = mutableMapOf<String, ClassLoader>()
    private val moduleClasses = mutableMapOf<String, Class<*>>()

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

    fun clearAll() {
        modules.clear()
        moduleClasses.clear()
    }
}