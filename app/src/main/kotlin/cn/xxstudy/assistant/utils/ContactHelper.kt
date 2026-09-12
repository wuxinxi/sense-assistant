package cn.xxstudy.assistant.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 手机通讯录查询工具类
 * 根据姓名/称谓精准或模糊匹配联系人，获取其电话号码
 */
object ContactHelper {
    private const val TAG = "ContactHelper"

    data class ContactMatch(
        val displayName: String,
        val phoneNumber: String
    )

    /**
     * 根据联系人称呼（如“老婆”、“张三”、“老妈”）检索电话号码
     * 匹配优先级：
     * 1. 姓名完全匹配（优先取默认主号码）
     * 2. 姓名模糊匹配（包含关键词）
     */
    fun findContact(context: Context, queryName: String): ContactMatch? {
        val trimmed = queryName.trim()
        if (trimmed.isBlank()) return null

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "缺少 READ_CONTACTS 权限，无法查询通讯录")
            return null
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
        )
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC"

        try {
            // 1. 优先尝试精确匹配
            val exactSelection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
            val exactArgs = arrayOf(trimmed)

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                exactSelection,
                exactArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    if (numIdx != -1 && nameIdx != -1) {
                        val num = cursor.getString(numIdx)
                        val name = cursor.getString(nameIdx)
                        if (!num.isNullOrBlank()) {
                            val cleanNum = cleanPhoneNumber(num)
                            Log.i(TAG, "精确命中联系人: $name -> $cleanNum")
                            return ContactMatch(name, cleanNum)
                        }
                    }
                }
            }

            // 2. 精确未命中，尝试模糊包含匹配 (LIKE %name%)
            val fuzzySelection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val fuzzyArgs = arrayOf("%$trimmed%")

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                fuzzySelection,
                fuzzyArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    if (numIdx != -1 && nameIdx != -1) {
                        val num = cursor.getString(numIdx)
                        val name = cursor.getString(nameIdx)
                        if (!num.isNullOrBlank()) {
                            val cleanNum = cleanPhoneNumber(num)
                            Log.i(TAG, "模糊命中联系人: $name -> $cleanNum")
                            return ContactMatch(name, cleanNum)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询通讯录异常: ${e.message}", e)
        }

        Log.w(TAG, "未在通讯录中找到联系人: $trimmed")
        return null
    }

    /**
     * 格式化清理电话号码，去除空格、破折号、括号等非号码字符
     */
    fun cleanPhoneNumber(raw: String): String {
        return raw.replace(Regex("[^0-9+*#]"), "")
    }
}
