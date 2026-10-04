package moe.uni.agent.settings

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.net.URI
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import moe.uni.agent.AgentFailure
import moe.uni.agent.util.readJson
import moe.uni.agent.util.writeText
import org.json.JSONObject

/** 模型端点设置（ADR-0003）：配置与 API key 的密文同一事务落盘；密钥永远由 Keystore 持有。 */
class SettingsStore(private val context: Context) {
    /**
     * effort 为空时不发送 reasoning.effort，由服务端决定推理强度。contextWindow 是模型的上下文窗口（token，ADR-0028）。
     * rootShell：是否把 root shell（exec、task）提供给 agent，默认关闭（ADR-0029 第 4 条）。
     */
    class Config(
        val baseUrl: String,
        val model: String,
        val reasoning: Boolean,
        val effort: String,
        val contextWindow: Long,
        val rootShell: Boolean,
    )

    private val file = AtomicFile(File(context.filesDir, FILE))

    fun config(): Config {
        val j = read()
        return Config(j.optString(BASE_URL), j.optString(MODEL), j.optBoolean(REASONING, true), j.optString(EFFORT),
            j.optLong(CONTEXT_WINDOW, DEFAULT_CONTEXT_WINDOW), j.optBoolean(ROOT_SHELL, false))
    }

    fun hasKey(): Boolean = read().has(CIPHERTEXT)

    /** 填写的内容不合要求；界面按 problem 显示说明。 */
    class Invalid(val problem: Problem) : IllegalArgumentException(problem.name)

    enum class Problem { BASE_URL_INVALID, BASE_URL_NOT_HTTPS, MODEL_MISSING, EFFORT_INVALID, KEY_MISSING, CONTEXT_WINDOW_INVALID }

    fun save(baseUrl: String, model: String, apiKey: String, reasoning: Boolean, effort: String, contextWindow: String, rootShell: Boolean) {
        val base = validateBase(baseUrl)
        ensure(model.isNotBlank(), Problem.MODEL_MISSING)
        ensure(effort.trim() in EFFORTS, Problem.EFFORT_INVALID)
        val window = contextWindow.trim().toLongOrNull()
        ensure(window != null && window > 0, Problem.CONTEXT_WINDOW_INVALID)
        val j = read()
        ensure(apiKey.isNotEmpty() || j.has(CIPHERTEXT), Problem.KEY_MISSING)
        if (apiKey.isNotEmpty()) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            j.put(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            j.put(CIPHERTEXT, Base64.encodeToString(cipher.doFinal(apiKey.toByteArray()), Base64.NO_WRAP))
        }
        j.put(BASE_URL, base).put(MODEL, model.trim()).put(REASONING, reasoning).put(EFFORT, effort.trim()).put(CONTEXT_WINDOW, window).put(ROOT_SHELL, rootShell)
        file.writeText(j.toString())
    }

    /** 运行前取出配置和 API key；没有填完整或解密失败时抛出给用户看的失败。 */
    fun require(): Pair<Config, String> {
        val config = config()
        if (config.baseUrl.isEmpty() || config.model.isEmpty() || !hasKey()) throw AgentFailure(AgentFailure.Reason.NOT_CONFIGURED)
        val key = try {
            apiKey()
        } catch (e: GeneralSecurityException) {
            throw AgentFailure(AgentFailure.Reason.KEY_UNREADABLE, cause = e)
        }
        return config to key
    }

    private fun apiKey(): String {
        val j = read()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, Base64.decode(j.getString(IV), Base64.NO_WRAP)))
        cipher.updateAAD(AAD)
        return cipher.doFinal(Base64.decode(j.getString(CIPHERTEXT), Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun read(): JSONObject = file.readJson() ?: JSONObject()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KEY_BITS).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setIsStrongBoxBacked(strongBox).build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        }
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            try { return generate(true) } catch (_: StrongBoxUnavailableException) { /* 按 ADR 回退。 */ }
        }
        return generate(false)
    }

    companion object {
        private const val FILE = "settings.json"

        // settings.json 的字段
        private const val BASE_URL = "baseUrl"
        private const val MODEL = "model"
        private const val REASONING = "reasoning"
        private const val EFFORT = "effort"
        private const val CONTEXT_WINDOW = "contextWindow"
        private const val ROOT_SHELL = "rootShell"
        private const val IV = "iv"
        private const val CIPHERTEXT = "ciphertext"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "agent.api-key.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private val AAD = "moe.uni.agent.api-key.v1".toByteArray()

        /** 上下文窗口的实现方默认值（ADR-0028 第 1 条）。 */
        const val DEFAULT_CONTEXT_WINDOW = 200_000L

        /** 允许的推理强度；空字符串表示不发送。 */
        val EFFORTS = listOf("", "none", "minimal", "low", "medium", "high", "xhigh")

        private const val SCHEME_HTTPS = "https"

        fun validateBase(value: String): String {
            val uri = try { URI(value.trim()) } catch (_: Exception) { throw Invalid(Problem.BASE_URL_INVALID) }
            ensure(uri.scheme == SCHEME_HTTPS && !uri.host.isNullOrEmpty(), Problem.BASE_URL_NOT_HTTPS)
            return uri.toASCIIString().trimEnd('/')
        }

        private fun ensure(ok: Boolean, problem: Problem) {
            if (!ok) throw Invalid(problem)
        }
    }
}
