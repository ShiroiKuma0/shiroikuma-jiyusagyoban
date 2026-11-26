package com.sysadmindoc.ruledeck.i18n

import com.sysadmindoc.ruledeck.core.actions.ActionMetadataRegistry
import com.sysadmindoc.ruledeck.core.actions.registerActionMetadata
import com.sysadmindoc.ruledeck.ui.theme.RoleSection
import com.sysadmindoc.ruledeck.ui.theme.UiRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * 白い熊, 2026-11-23: every string gets a full Japanese translation, and the key sets are compared
 * before a build is called done. Upstream's resources are compared file by file; the fork's own
 * text, which is translated by its English literal ([tr]), is compared literal by literal.
 */
class JapaneseTranslationTest {
    private val moduleRoot: Path = listOf(Path.of("."), Path.of("app")).first { Files.isDirectory(it.resolve("src/main")) }
    private val placeholder = Regex("""%(?:\d+\$)?(?:\.\d+)?[sdfxXc]|%%""")

    @After
    fun followTheDeviceAgain() {
        Translations.forcedLanguage = null
    }

    private fun resourceKeys(dir: String, file: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(moduleRoot.resolve("src/main/res/$dir/$file").toFile())
        val out = LinkedHashMap<String, String>()
        val root = document.documentElement.childNodes
        for (i in 0 until root.length) {
            val node = root.item(i)
            val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
            if (node.attributes?.getNamedItem("translatable")?.nodeValue == "false") continue
            if (node.nodeName == "string") {
                out["string/$name"] = node.textContent
            } else {
                val items = node.childNodes
                var n = 0
                for (j in 0 until items.length) {
                    val item = items.item(j)
                    if (item.nodeName != "item") continue
                    val quantity = item.attributes?.getNamedItem("quantity")?.nodeValue ?: (n++).toString()
                    out["${node.nodeName}/$name/$quantity"] = item.textContent
                }
            }
        }
        return out
    }

    @Test
    fun everyResourceKeyHasItsJapanese() {
        listOf("strings.xml", "action_catalog_strings.xml", "dynamic_surface_strings.xml").forEach { file ->
            val english = resourceKeys("values", file)
            val japanese = resourceKeys("values-ja", file)
            assertEquals("$file: keys missing from values-ja", emptySet<String>(), english.keys - japanese.keys)
            val broken = english.keys.filter { key ->
                placeholder.findAll(english.getValue(key)).map { it.value }.sorted().toList() !=
                    placeholder.findAll(japanese.getValue(key)).map { it.value }.sorted().toList()
            }
            assertEquals("$file: placeholders differ", emptyList<String>(), broken)
        }
    }

    @Test
    fun theTablesAreWellFormedAndListed() {
        val dir = moduleRoot.resolve("src/main/resources/i18n/ja")
        val listed = dir.resolve("index.txt").readText().lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val present = Files.list(dir).use { files -> files.map { it.name }.filter { it != "index.txt" }.toList().toSet() }
        assertEquals("index.txt must list every table", present, listed)
        val problems = ArrayList<String>()
        val seen = HashMap<String, String>()
        present.sorted().forEach { name ->
            Translations.parse(dir.resolve(name).readText().lineSequence()) { key, value ->
                val english = key.substringAfter('|', key).takeIf { Regex("^[a-z_-]+\\|").containsMatchIn(key) } ?: key
                if (placeholder.findAll(english).map { it.value }.sorted().toList() !=
                    placeholder.findAll(value).map { it.value }.sorted().toList()
                ) problems += "$name: placeholders differ for “$key”"
                if ("フォルダ" in value) problems += "$name: フォルダ in “$key” — a directory is ディレクトリ"
                val other = seen.put(key, value)
                if (other != null && other != value) problems += "$name: “$key” has two translations"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    /** Every literal handed to tr() / trc() in the sources has an entry. */
    @Test
    fun everyTranslatedLiteralHasItsJapanese() {
        val table = Translations.table("ja")
        val literal = """"((?:[^"\\]|\\.)*)""""
        val plain = Regex("""\btr\(\s*$literal""")
        val withContext = Regex("""\btrc\(\s*$literal\s*,\s*$literal""")
        fun unescape(text: String) = Regex("""\\(.)""").replace(text) { match ->
            when (val c = match.groupValues[1]) { "n" -> "\n"; "t" -> "\t"; else -> c }
        }
        val missing = ArrayList<String>()
        Files.walk(moduleRoot.resolve("src/main/java")).use { paths ->
            paths.filter { it.name.endsWith(".kt") && it.name != "Tr.kt" }.forEach { file ->
                val source = file.readText()
                plain.findAll(source).forEach { if (unescape(it.groupValues[1]) !in table) missing += "${file.name}: ${it.groupValues[1]}" }
                withContext.findAll(source).forEach {
                    val key = unescape(it.groupValues[1]) + "|" + unescape(it.groupValues[2])
                    if (key !in table && unescape(it.groupValues[2]) !in table) missing += "${file.name}: $key"
                }
            }
        }
        assertEquals(emptyList<String>(), missing)
    }

    /** The tables that are shown through tr(variable): the action catalogue and the colour roles. */
    @Test
    fun theCatalogueAndTheRolesHaveTheirJapanese() {
        registerActionMetadata()
        val table = Translations.table("ja")
        val texts = LinkedHashSet<String>()
        ActionMetadataRegistry.all().forEach { action ->
            texts += action.name; texts += action.description; texts += action.category
            action.fields.forEach { field -> texts += field.label; field.hint?.let { texts += it } }
        }
        RoleSection.entries.forEach { texts += it.title }
        UiRole.entries.forEach { texts += it.label; texts += it.note }
        // A text with no letter of the Latin alphabet (a value, a name already Japanese) needs none.
        val missing = texts.filter { text -> text.any { it in 'a'..'z' || it in 'A'..'Z' } && text !in table && "role|$text" !in table }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun englishShowsTheLiteralAndJapaneseItsTranslation() {
        Translations.forcedLanguage = "en"
        assertEquals("Reset section order", tr("Reset section order"))
        Translations.forcedLanguage = "ja"
        assertEquals("セクションの順番をリセット", tr("Reset section order"))
        assertTrue(tr("No such text anywhere") == "No such text anywhere")
    }
}
