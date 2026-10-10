package com.java.myapplication.engine

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 禁漫天堂（18comic）APP API 的加密/解密工具。
 *
 * 全部算法与密钥常量均为开源库 jmcomic 的公开常量（非用户凭证），
 * 实现它们不涉及任何用户私密信息，符合「App 不内置用户密钥」的安全红线。
 *
 * 链路：
 *   token        = md5hex("{ts}{APP_TOKEN_SECRET}")
 *   tokenparam   = "{ts},{APP_VERSION}"
 *   resp.data    = base64 密文 → AES-ECB 解密(key=md5hex("{ts}{APP_DATA_SECRET}")) → 去 PKCS7 → JSON
 *   scramble_num = getNum(scramble_id, aid, filename)，决定图片条带数
 */
object JmCrypto {

    /**
     * 禁漫 APP 版本号。
     *
     * 【重要】不能用 const：jmcomic 的 `setting()` 会在每次请求 `/setting` 后，
     * 把返回体里的 `jm3_version` 与当前值比较，若服务端更新则替换为较新版本
     * （jm_client_impl.py:945-959，FLAG_USE_VERSION_NEWER_IF_BEHIND=True）。
     * 该版本号会参与 tokenparam = "{ts},{APP_VERSION}"，偏旧可能导致鉴权被拒。
     * 实测（用户成功日志）：服务端已下发 2.1.11，故这里必须可动态更新。
     */
    @Volatile
    var APP_VERSION = "2.1.7"

    const val APP_TOKEN_SECRET = "185Hcomic3PAPP7R"
    const val APP_DATA_SECRET = "185Hcomic3PAPP7R"
    const val APP_TOKEN_SECRET_2 = "18comicAPPContent"

    const val SCRAMBLE_220980 = 220980

    /**
     * 比较两个版本号字符串（形如 "2.1.7"）。
     * @return v1 > v2 返回 1；v1 < v2 返回 -1；相等返回 0。
     * 对齐 jmcomic JmcomicText.compare_versions 的语义（按 . 分段逐段比较）。
     */
    fun compareVersions(v1: String, v2: String): Int {
        val a = v1.trim().split(".")
        val b = v2.trim().split(".")
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrNull(i)?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /**
     * 若服务端版本较新则更新 APP_VERSION（对齐 jmcomic setting() 的行为）。
     * @param serverVer /setting 返回体里的 jm3_version
     * @return 是否发生了更新
     */
    fun updateAppVersionIfNewer(serverVer: String): Boolean {
        val sv = serverVer.trim()
        if (sv.isEmpty()) return false
        if (compareVersions(sv, APP_VERSION) == 1) {
            APP_VERSION = sv
            return true
        }
        return false
    }

    /** 十六进制小写 MD5 */
    fun md5hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /** 生成请求鉴权头：token + tokenparam（ts 为秒级时间戳） */
    fun authHeaders(ts: Long, secret: String = APP_TOKEN_SECRET): Map<String, String> {
        val token = md5hex("$ts$secret")
        val tokenparam = "$ts,$APP_VERSION"
        return mapOf(
            "token" to token,
            "tokenparam" to tokenparam
        )
    }

    /**
     * 解密 `/album`、`/chapter` 等接口返回的密文。
     * @param data base64 密文（JSON 里的 "data" 字段）
     * @param ts 请求时用的秒级时间戳
     * @param secret 对应接口的数据密钥
     */
    fun decodeRespData(data: String, ts: Long, secret: String = APP_DATA_SECRET): String {
        val key = md5hex("$ts$secret").toByteArray(Charsets.UTF_8) // 32 字符 → AES-256 key
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val raw = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (_: Exception) {
            // 兼容 URL-safe base64
            Base64.decode(data, Base64.URL_SAFE)
        }
        val plain = cipher.doFinal(raw)
        return String(plain, Charsets.UTF_8)
    }

    /**
     * scramble 条带数算法（对齐 jmcomic JmImageTool.get_num）。
     * @param scrambleId 该章节的 scramble_id
     * @param aid 漫画主 id
     * @param filename 图片文件名（含后缀，如 "00047.webp"）
     * @return 条带数；0 表示不处理
     */
    fun scrambleNum(scrambleId: Long, aid: Long, filename: String): Int {
        if (aid < scrambleId) return 0
        if (aid < 268850L) return 10
        val x = if (aid < 421926L) 10 else 8
        val h = md5hex("$aid$filename")
        val last = h[h.length - 1]
        return (last.code % x) * 2 + 2
    }
}
