package one.njk.sao.floating

/** Classification never replaces the actual recipient package with an official app. */
object AppFamilyResolver {
    fun resolve(packageName: String?, customTelegram: Set<String>): MessagingTarget {
        val builtIn = MessagingTarget.fromPackage(packageName)
        return if (builtIn != MessagingTarget.OTHER) builtIn
        else if (packageName != null && packageName in customTelegram) MessagingTarget.TELEGRAM
        else MessagingTarget.OTHER
    }
    fun supported(customTelegram: Set<String>) =
        (MessagingTarget.entries.flatMap { it.packages } + customTelegram).toSet()
}

object PackageNames {
    private val pattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun valid(value: String) = pattern.matches(value)
    data class Parsed(val packages: Set<String>, val invalid: List<String>)
    fun parse(value: String): Parsed {
        val tokens = value.split(',', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
        return Parsed(tokens.filter(::valid).toSet(), tokens.filterNot(::valid))
    }
}
