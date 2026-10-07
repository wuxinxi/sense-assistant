package cn.xxstudy.assistant.security

/** Fail closed until authenticated API ownership and explicit action authorization are implemented. */
object CommercialSafetyPolicy {
    const val externalApiAvailable = false
    const val automaticModelActionsAllowed = false
    const val externalApiStatus = "外部接口暂不可用：正在升级鉴权与会话隔离"
}
