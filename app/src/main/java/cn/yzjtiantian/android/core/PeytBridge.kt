package cn.yzjtiantian.android.core

/**
 * JNI entry points into the Rust `peytchat-bridge` shared library.
 *
 * All native methods are synchronous; the JSON-RPC call is performed with a
 * blocking call against the deltachat core session held globally in Rust.
 */
object PeytBridge {
    init {
        System.loadLibrary("peytchat_bridge")
    }

    external fun nativeInit(dir: String): Long
    external fun nativePluginsInit(appDataDir: String): Long
    external fun nativeJsonrpcCall(request: String): String
    external fun nativeJsonrpcRequest(request: String)
    external fun nativeJsonrpcNextResponse(): String
    external fun nativeUnref()
    external fun nativePluginsFetchRegistry(): String
    external fun nativePluginsInstall(name: String): String
    external fun nativePluginsInstallFromZip(dataBase64: String): String
    external fun nativePluginsUninstall(name: String): String
    external fun nativePluginsList(): String
    external fun nativePluginsToggle(name: String, enabled: Boolean): String
    external fun nativePluginsGetJs(name: String): String
}
