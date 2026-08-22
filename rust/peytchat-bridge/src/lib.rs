use std::ffi::{c_char, c_int, CStr, CString};
use std::path::PathBuf;
use std::sync::Mutex;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring};
use jni::JNIEnv;
use peytchat_plugins::PluginManager;

#[repr(C)]
struct AccountsTag;

extern "C" {
    fn dc_accounts_new(dir: *const c_char, writable: c_int) -> *const AccountsTag;
    fn dc_accounts_unref(accounts: *const AccountsTag);
    fn dc_jsonrpc_init(account_manager: *const AccountsTag) -> *mut RpcInstanceTag;
    fn dc_jsonrpc_unref(jsonrpc_instance: *mut RpcInstanceTag);
    fn dc_jsonrpc_request(jsonrpc_instance: *mut RpcInstanceTag, request: *const c_char);
    fn dc_jsonrpc_next_response(jsonrpc_instance: *mut RpcInstanceTag) -> *mut c_char;
    fn dc_jsonrpc_blocking_call(jsonrpc_instance: *mut RpcInstanceTag, input: *const c_char) -> *mut c_char;
    fn dc_str_unref(s: *mut c_char);
}

#[repr(C)]
struct RpcInstanceTag;

/// Opaque global handle to the deltachat JSON-RPC session.
struct RpcHandle {
    accounts: *const AccountsTag,
    jsonrpc: *mut RpcInstanceTag,
}

unsafe impl Send for RpcHandle {}
unsafe impl Sync for RpcHandle {}

static RPC: Mutex<Option<RpcHandle>> = Mutex::new(None);
static PLUGINS: Mutex<Option<PluginManager>> = Mutex::new(None);

fn c_str_bytes(s: &str) -> Option<CString> {
    CString::new(s).ok()
}

fn char_ptr_to_string(ptr: *mut c_char) -> String {
    if ptr.is_null() {
        return String::new();
    }
    let result = unsafe { CStr::from_ptr(ptr) }
        .to_string_lossy()
        .into_owned();
    unsafe { dc_str_unref(ptr) };
    result
}

/// Copy the raw RPC pointers out under a short lock.
///
/// The handle is installed once by `nativeInit` and only removed by
/// `nativeUnref` at teardown, so grabbing the pointers briefly and calling
/// the blocking FFI *without* holding the mutex is safe. Holding the mutex
/// across `dc_jsonrpc_blocking_call` would deadlock: `get_next_event_batch`
/// blocks until an event arrives, stalling every concurrent RPC call.
fn rpc_pointers() -> Option<(*const AccountsTag, *mut RpcInstanceTag)> {
    RPC.lock()
        .ok()?
        .as_ref()
        .map(|h| (h.accounts, h.jsonrpc))
}

/// Initialize the deltachat accounts manager and JSON-RPC session.
///
/// Returns 1 on success, 0 on failure.
#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
) -> jlong {
    let dir_str = env
        .get_string(&dir)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();

    let Some(dir_c) = c_str_bytes(&dir_str) else {
        return 0;
    };

    let accounts = unsafe { dc_accounts_new(dir_c.as_ptr(), 1) };
    if accounts.is_null() {
        return 0;
    }

    let jsonrpc = unsafe { dc_jsonrpc_init(accounts) };
    if jsonrpc.is_null() {
        unsafe { dc_accounts_unref(accounts) };
        return 0;
    }

    let handle = RpcHandle { accounts, jsonrpc };
    match RPC.lock() {
        Ok(mut guard) => {
            *guard = Some(handle);
            1
        }
        Err(_) => {
            unsafe { dc_jsonrpc_unref(jsonrpc) };
            unsafe { dc_accounts_unref(accounts) };
            0
        }
    }
}

/// Initialize the plugin manager for the given app data dir.
#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativePluginsInit(
    mut env: JNIEnv,
    _class: JClass,
    app_data_dir: JString,
) -> jlong {
    let dir_str = env
        .get_string(&app_data_dir)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();

    let manager = PluginManager::new(PathBuf::from(dir_str));
    match PLUGINS.lock() {
        Ok(mut guard) => {
            *guard = Some(manager);
            1
        }
        Err(_) => 0,
    }
}

/// Perform a synchronous JSON-RPC request against the deltachat core.
///
/// Returns the JSON response as a Java string (possibly empty on failure).
#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativeJsonrpcCall(
    mut env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    let request_str = env
        .get_string(&request)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();

    let response = rpc_pointers()
        .and_then(|(_accounts, jsonrpc)| {
            let Some(input) = c_str_bytes(&request_str) else {
                return None;
            };
            let ptr = unsafe { dc_jsonrpc_blocking_call(jsonrpc, input.as_ptr()) };
            Some(char_ptr_to_string(ptr))
        })
        .unwrap_or_default();

    env.new_string(&response)
        .ok()
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Send an asynchronous JSON-RPC request (fire-and-forget).
#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativeJsonrpcRequest(
    mut env: JNIEnv,
    _class: JClass,
    request: JString,
) {
    let request_str = env
        .get_string(&request)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();

    if let Some((_accounts, jsonrpc)) = rpc_pointers() {
        if let Some(input) = c_str_bytes(&request_str) {
            unsafe { dc_jsonrpc_request(jsonrpc, input.as_ptr()) };
        }
    }
}

/// Block until the next JSON-RPC response is available and return it.
#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativeJsonrpcNextResponse(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let response = rpc_pointers()
        .map(|(_accounts, jsonrpc)| {
            let ptr = unsafe { dc_jsonrpc_next_response(jsonrpc) };
            char_ptr_to_string(ptr)
        })
        .unwrap_or_default();

    env.new_string(&response)
        .ok()
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Release the global JSON-RPC session.
#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativeUnref() {
    if let Ok(mut guard) = RPC.lock() {
        if let Some(handle) = guard.take() {
            unsafe { dc_jsonrpc_unref(handle.jsonrpc) };
            unsafe { dc_accounts_unref(handle.accounts) };
        }
    }
}

fn with_plugins<F, T>(f: F) -> Option<T>
where
    F: FnOnce(&PluginManager) -> T,
{
    let guard = PLUGINS.lock().ok()?;
    let manager = guard.as_ref()?;
    Some(f(manager))
}

fn json_result<T: serde::Serialize>(result: std::result::Result<T, peytchat_plugins::PluginError>) -> String {
    match result {
        Ok(value) => serde_json::to_string(&value).unwrap_or_else(|_| "[]".into()),
        Err(e) => format!("{{\"error\":{}}}", serde_json::to_string(&e.to_string()).unwrap_or_default()),
    }
}

#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativePluginsFetchRegistry(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = with_plugins(|p| p.fetch_registry()).unwrap_or_else(|| Err(peytchat_plugins::PluginError::Plugin("plugin manager not initialized".into())));
    let out = json_result(result);
    env.new_string(&out).ok().map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativePluginsInstall(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    let name_str = env
        .get_string(&name)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let result = with_plugins(|p| p.install_plugin(&name_str))
        .unwrap_or_else(|| Err(peytchat_plugins::PluginError::Plugin("plugin manager not initialized".into())));
    let out = json_result(result);
    env.new_string(&out).ok().map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativePluginsInstallFromZip(
    mut env: JNIEnv,
    _class: JClass,
    data_base64: JString,
) -> jstring {
    let data_str = env
        .get_string(&data_base64)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let result = with_plugins(|p| p.install_plugin_from_zip(&data_str))
        .unwrap_or_else(|| Err(peytchat_plugins::PluginError::Plugin("plugin manager not initialized".into())));
    let out = json_result(result);
    env.new_string(&out).ok().map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativePluginsUninstall(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    let name_str = env
        .get_string(&name)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let result = with_plugins(|p| p.uninstall_plugin(&name_str))
        .unwrap_or_else(|| Err(peytchat_plugins::PluginError::Plugin("plugin manager not initialized".into())));
    let out = json_result(result.map(|_| true));
    env.new_string(&out).ok().map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativePluginsList(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = with_plugins(|p| p.list_plugins())
        .unwrap_or_else(|| Err(peytchat_plugins::PluginError::Plugin("plugin manager not initialized".into())));
    let out = json_result(result);
    env.new_string(&out).ok().map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativePluginsToggle(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    enabled: jboolean,
) -> jstring {
    let name_str = env
        .get_string(&name)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let result = with_plugins(|p| p.toggle_plugin(&name_str, enabled == 1))
        .unwrap_or_else(|| Err(peytchat_plugins::PluginError::Plugin("plugin manager not initialized".into())));
    let out = json_result(result.map(|_| true));
    env.new_string(&out).ok().map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_cn_yzjtiantian_android_core_PeytBridge_nativePluginsGetJs(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    let name_str = env
        .get_string(&name)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let result = with_plugins(|p| p.get_plugin_js(&name_str))
        .unwrap_or_else(|| Err(peytchat_plugins::PluginError::Plugin("plugin manager not initialized".into())));
    match result {
        Ok(js) => env
            .new_string(&js)
            .ok()
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            let out = format!("{{\"error\":{}}}", serde_json::to_string(&e.to_string()).unwrap_or_default());
            env.new_string(&out).ok().map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
        }
    }
}
