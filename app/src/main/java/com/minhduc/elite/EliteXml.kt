package com.minhduc.elite

import org.w3c.dom.Element
import java.io.InputStream
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class ImportReport(
    val imported: List<GameProfile>,
    val skipped: List<String>,
    val rootTag: String
) {
    val summary: String
        get() = buildString {
            append("Nạp ${imported.size} profile")
            if (skipped.isNotEmpty()) append(", bỏ qua ${skipped.size} entry lỗi")
            append(" (root: <$rootTag>)")
        }
}

object EliteXml {
    private val VALID_ROOTS = setOf(
        "MINHDUC_ENGINE", "APP", "PROFILES", "PROFILE_LIST", "MINHDUC"
    )

    fun import(input: InputStream): ImportReport {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        val doc = factory.newDocumentBuilder().parse(input)
        val root = doc.documentElement
            ?: return ImportReport(emptyList(), listOf("File XML rỗng"), "?")
        val rootTag = root.tagName.uppercase()

        val imported = mutableListOf<GameProfile>()
        val skipped = mutableListOf<String>()

        val candidates = buildList {
            addAll(childElements(root, "profile"))
            addAll(childElements(root, "app"))
            addAll(childElements(root, "entry"))
            addAll(childElements(root, "item"))
            if (isEmpty() && root.hasAttribute("package")) add(root)
        }

        candidates.forEachIndexed { idx, el ->
            val pkg = attr(el, "package")
                ?: childText(el, "package")
                ?: childText(el, "packageName")
            if (pkg.isNullOrBlank()) {
                skipped += "Entry #$idx: thiếu <package>"
                return@forEachIndexed
            }
            val name = attr(el, "name")
                ?: childText(el, "name")
                ?: childText(el, "appName")
                ?: pkg.substringAfterLast('.')
            val preset = attr(el, "preset") ?: childText(el, "preset") ?: "BALANCED"
            val sens = (attr(el, "sensitivity") ?: childText(el, "sensitivity"))
                ?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.85f
            val smooth = (attr(el, "smoothness") ?: childText(el, "smoothness"))
                ?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.90f

            imported += GameProfile(pkg, name, preset, sens, smooth)
        }

        if (imported.isEmpty() && skipped.isEmpty()) {
            skipped += "Không tìm thấy node profile hợp lệ trong <$rootTag>"
        }
        return ImportReport(imported, skipped, rootTag)
    }

    fun export(profiles: List<GameProfile>, output: OutputStream) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = doc.createElement("MINHDUC_ENGINE").apply { setAttribute("version", "4.0") }
        doc.appendChild(root)
        profiles.forEach { p ->
            val e = doc.createElement("profile").apply {
                setAttribute("package", p.packageName)
                setAttribute("name", p.appName)
                setAttribute("preset", p.preset)
                setAttribute("sensitivity", p.sensitivity.toString())
                setAttribute("smoothness", p.smoothness.toString())
            }
            root.appendChild(e)
        }
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(doc), StreamResult(output))
    }

    private fun childElements(parent: Element, tag: String): List<Element> {
        val out = mutableListOf<Element>()
        val nl = parent.getElementsByTagName("*")
        for (i in 0 until nl.length) {
            val e = nl.item(i) as? Element ?: continue
            if (e.tagName.equals(tag, ignoreCase = true)) out += e
        }
        return out
    }

    private fun attr(el: Element, name: String): String? =
        if (el.hasAttribute(name)) el.getAttribute(name).takeIf { it.isNotBlank() } else null

    private fun childText(el: Element, tag: String): String? {
        val nl = el.getElementsByTagName(tag)
        if (nl.length == 0) return null
        return (nl.item(0) as? Element)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }
}
