package com.java.myapplication.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Cookie 存储与导入。
 *
 * 安全红线（务必遵守）：
 * 1. 本类只提供「运行时从用户选的文件导入」的能力，代码内绝不内置任何 cookie 内容。
 * 2. cookie 存到 App 私有目录（filesDir），不写外部存储、不上传、不进相册。
 * 3. 提供清除能力。
 */
object CookieStore {
    private const val FILE = "cookies.txt"

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    fun hasCookie(ctx: Context): Boolean = file(ctx).exists() && file(ctx).length() > 0

    fun cookiePath(ctx: Context): String = file(ctx).absolutePath

    /**
     * 从用户选择的 Uri 导入 cookie 文件。
     * @return 导入后的字节数，失败返回 -1
     */
    fun import(ctx: Context, uri: Uri): Long {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val target = file(ctx)
                target.outputStream().use { out -> input.copyTo(out) }
                target.length()
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /**
     * 解析 Netscape 格式 cookies.txt，拼接成 Cookie 请求头，写入 [Http.cookieHeader]。
     * 无 cookie 文件时清空全局 cookieHeader。
     */
    fun loadIntoHeader(ctx: Context) {
        val f = file(ctx)
        if (!f.exists() || f.length() == 0L) {
            com.java.myapplication.engine.Http.cookieHeader = null
            return
        }
        val pairs = mutableListOf<String>()
        f.readLines().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            // Netscape: domain  flag  path  secure  expires  name  value
            val parts = t.split('\t')
            if (parts.size >= 7) {
                pairs.add("${parts[5]}=${parts[6]}")
            }
        }
        com.java.myapplication.engine.Http.cookieHeader = if (pairs.isEmpty()) null else pairs.joinToString("; ")
    }

    /**
     * 从当前运行时 cookie 头里取指定名字的 cookie 值（找不到返回 null）。
     * 用途：Twitter 登录态 GraphQL 接口需要 `ct0` 作为 `x-csrf-token`。
     * 注意：只读内存中的 cookieHeader，绝不落盘、绝不内置。
     */
    fun valueOf(name: String): String? {
        val header = com.java.myapplication.engine.Http.cookieHeader ?: return null
        header.split(';').forEach { seg ->
            val s = seg.trim()
            val eq = s.indexOf('=')
            if (eq > 0 && s.substring(0, eq).trim() == name) {
                return s.substring(eq + 1).trim()
            }
        }
        return null
    }
}