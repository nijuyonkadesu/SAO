package one.njk.sao.source

fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
