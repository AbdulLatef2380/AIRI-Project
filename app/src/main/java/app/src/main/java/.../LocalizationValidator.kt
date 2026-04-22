import android.content.Context

object LocalizationValidator {

    fun validate(context: Context): List<String> {
        val base = context.resources.getAssets().open("res/values/strings.xml")
        val ar = context.resources.getAssets().open("res/values-ar/strings.xml")

        val baseKeys = extractKeys(base.readBytes().decodeToString())
        val arKeys = extractKeys(ar.readBytes().decodeToString())

        return baseKeys.filter { it !in arKeys }
    }

    private fun extractKeys(xml: String): Set<String> {
        val regex = """name="([^"]+)"""".toRegex()
        return regex.findAll(xml)
            .map { it.groupValues[1] }
            .toSet()
    }
}
