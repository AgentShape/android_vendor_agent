/*
 * exec_js 的 QuickJS 桥（ADR-0024）。只注入一个同步宿主函数 __host(name, json) → json，
 * 其余接口在 prelude（JS）里实现。字符串一律按 UTF-8 字节传递，避开 JNI 的 modified UTF-8。
 */
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "quickjs.h"

typedef struct {
    JSRuntime *rt;
    JSContext *ctx;
    /* 只在 nativeEval 期间有效：当前线程的 env 和宿主对象。 */
    JNIEnv *env;
    jobject host;
    jmethodID call;
    atomic_int interrupted;
    int64_t deadline;
} Engine;

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* 停止按钮和超时都在这里打断，死循环也能停下。 */
static int interrupt_handler(JSRuntime *rt, void *opaque) {
    Engine *e = opaque;
    return atomic_load(&e->interrupted) || (e->deadline > 0 && now_ms() > e->deadline);
}

static jbyteArray to_bytes(JNIEnv *env, const char *s, size_t len) {
    jbyteArray a = (*env)->NewByteArray(env, (jsize)len);
    if (a) (*env)->SetByteArrayRegion(env, a, 0, (jsize)len, (const jbyte *)s);
    return a;
}

/* __host(name, json)：同步调用 Kotlin 的 ScriptHost.call(String, byte[]) → byte[]。 */
static JSValue js_host(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = JS_GetContextOpaque(ctx);
    if (argc < 2 || !e->env) return JS_ThrowInternalError(ctx, "host unavailable");
    const char *name = JS_ToCString(ctx, argv[0]);
    size_t len;
    const char *args = JS_ToCStringLen(ctx, &len, argv[1]);
    JSValue ret = JS_EXCEPTION;
    if (name && args) {
        JNIEnv *env = e->env;
        jstring jname = (*env)->NewStringUTF(env, name);
        jbyteArray jargs = to_bytes(env, args, len);
        jbyteArray result = (jbyteArray)(*env)->CallObjectMethod(env, e->host, e->call, jname, jargs);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            ret = JS_ThrowInternalError(ctx, "host call %s failed", name);
        } else {
            jsize n = (*env)->GetArrayLength(env, result);
            jbyte *bytes = (*env)->GetByteArrayElements(env, result, NULL);
            ret = JS_NewStringLen(ctx, (const char *)bytes, n);
            (*env)->ReleaseByteArrayElements(env, result, bytes, JNI_ABORT);
        }
        (*env)->DeleteLocalRef(env, jname);
        (*env)->DeleteLocalRef(env, jargs);
        if (result) (*env)->DeleteLocalRef(env, result);
    }
    JS_FreeCString(ctx, name);
    JS_FreeCString(ctx, args);
    return ret;
}

/* 取出当前异常的文字（含栈）。 */
static char *exception_text(JSContext *ctx) {
    JSValue ex = JS_GetException(ctx);
    const char *msg = JS_ToCString(ctx, ex);
    const char *stack = NULL;
    if (JS_IsObject(ex)) {
        JSValue s = JS_GetPropertyStr(ctx, ex, "stack");
        if (!JS_IsUndefined(s)) stack = JS_ToCString(ctx, s);
        JS_FreeValue(ctx, s);
    }
    size_t size = strlen(msg ? msg : "") + strlen(stack ? stack : "") + 2;
    char *text = malloc(size);
    snprintf(text, size, "%s\n%s", msg ? msg : "", stack ? stack : "");
    JS_FreeCString(ctx, msg);
    JS_FreeCString(ctx, stack);
    JS_FreeValue(ctx, ex);
    return text;
}

JNIEXPORT jlong JNICALL Java_moe_uni_agent_tools_js_ScriptEngine_nativeCreate(JNIEnv *env, jclass cls,
        jlong memoryLimit, jlong stackLimit) {
    Engine *e = calloc(1, sizeof(Engine));
    e->rt = JS_NewRuntime();
    JS_SetMemoryLimit(e->rt, (size_t)memoryLimit);
    JS_SetMaxStackSize(e->rt, (size_t)stackLimit);
    JS_SetInterruptHandler(e->rt, interrupt_handler, e);
    e->ctx = JS_NewContext(e->rt);
    JS_SetContextOpaque(e->ctx, e);
    JSValue global = JS_GetGlobalObject(e->ctx);
    JS_SetPropertyStr(e->ctx, global, "__host", JS_NewCFunction(e->ctx, js_host, "__host", 2));
    JS_FreeValue(e->ctx, global);
    return (jlong)(intptr_t)e;
}

JNIEXPORT void JNICALL Java_moe_uni_agent_tools_js_ScriptEngine_nativeDestroy(JNIEnv *env, jclass cls, jlong ptr) {
    Engine *e = (Engine *)(intptr_t)ptr;
    JS_FreeContext(e->ctx);
    JS_FreeRuntime(e->rt);
    free(e);
}

JNIEXPORT void JNICALL Java_moe_uni_agent_tools_js_ScriptEngine_nativeInterrupt(JNIEnv *env, jclass cls, jlong ptr) {
    atomic_store(&((Engine *)(intptr_t)ptr)->interrupted, 1);
}

/*
 * 在全局作用域执行 UTF-8 源码。返回值第一个字节表示结果：'R' 后面是结果转成的字符串，
 * 'E' 后面是未捕获的异常（包括中断和超时）。timeoutMs 为 0 时不限时。
 */
JNIEXPORT jbyteArray JNICALL Java_moe_uni_agent_tools_js_ScriptEngine_nativeEval(JNIEnv *env, jclass cls, jlong ptr,
        jobject host, jbyteArray code, jstring name, jlong timeoutMs) {
    Engine *e = (Engine *)(intptr_t)ptr;
    e->env = env;
    e->host = host;
    e->call = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, host), "call", "(Ljava/lang/String;[B)[B");
    if (!e->call) {
        (*env)->ExceptionClear(env);
        const char err[] = "Ehost has no call(String, byte[]) method";
        return to_bytes(env, err, sizeof(err) - 1);
    }
    atomic_store(&e->interrupted, 0);
    e->deadline = timeoutMs > 0 ? now_ms() + timeoutMs : 0;
    JS_UpdateStackTop(e->rt);

    jsize len = (*env)->GetArrayLength(env, code);
    char *src = malloc((size_t)len + 1);
    (*env)->GetByteArrayRegion(env, code, 0, len, (jbyte *)src);
    src[len] = 0;
    const char *file = (*env)->GetStringUTFChars(env, name, NULL);
    JSValue v = JS_Eval(e->ctx, src, (size_t)len, file, JS_EVAL_TYPE_GLOBAL);
    (*env)->ReleaseStringUTFChars(env, name, file);
    free(src);

    char *text;
    char kind;
    if (JS_IsException(v)) {
        kind = 'E';
        text = exception_text(e->ctx);
    } else {
        kind = 'R';
        const char *s = JS_IsUndefined(v) ? NULL : JS_ToCString(e->ctx, v);
        text = strdup(s ? s : "");
        JS_FreeCString(e->ctx, s);
    }
    JS_FreeValue(e->ctx, v);
    e->env = NULL;
    e->host = NULL;

    size_t n = strlen(text);
    jbyteArray out = (*env)->NewByteArray(env, (jsize)(n + 1));
    (*env)->SetByteArrayRegion(env, out, 0, 1, (const jbyte *)&kind);
    (*env)->SetByteArrayRegion(env, out, 1, (jsize)n, (const jbyte *)text);
    free(text);
    return out;
}
