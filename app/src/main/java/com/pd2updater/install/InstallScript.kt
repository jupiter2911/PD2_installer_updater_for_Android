package com.pd2updater.install

import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

sealed class Operation {
    /** Copy Tome member [from] to file [to] of the game folder. */
    data class Extract(val from: String, val to: String, val size: Long?) : Operation()

    /** Add Tome member [from] to archive [container] as [to]. */
    data class Add(val container: String, val from: String, val to: String, val size: Long?) : Operation()

    /** Store the key for [product] in archive [container] as [into]. */
    data class Key(val container: String, val into: String, val product: Int) : Operation()

    /** Store the owner name in archive [container] as [into]. */
    data class Owner(val container: String, val into: String) : Operation()

    /** Delete plain file [name] of the game folder, when it is there. */
    data class Delete(val name: String) : Operation()
}

class ScriptException(message: String) : Exception(message)

/**
 * The install script in each Installer Tome, read for the Windows English
 * install into a plan. Unknown elements and conditions fail the read.
 * Ported from pd2-launcher `blizzard/script.rs`.
 */
object InstallScript {
    private const val MAX_OPERATIONS = 512
    private const val MAX_DEPTH = 64

    private class Into(val kind: String, val container: String?)

    private class Scope(val user: Boolean = false, val archive: Boolean = false, val into: Into? = null) {
        fun with(user: Boolean = this.user, archive: Boolean = this.archive, into: Into? = this.into) =
            Scope(user, archive, into)
    }

    fun plan(script: String): List<Operation> {
        val text = script.removePrefix("﻿")
        val document = try {
            val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            builder.setErrorHandler(object : ErrorHandler {
                override fun warning(exception: SAXParseException) {}
                override fun error(exception: SAXParseException) = throw exception
                override fun fatalError(exception: SAXParseException) = throw exception
            })
            builder.parse(InputSource(StringReader(text)))
        } catch (_: SAXException) {
            throw ScriptException("xml")
        }
        val root = document.documentElement
        if (root.tagName != "install" && root.tagName != "multipass_install") {
            throw ScriptException("element ${root.tagName}")
        }
        val walk = Walk()
        walk.children(root, Scope())
        return walk.operations
    }

    private fun elements(node: Element): List<Element> {
        val nodes = node.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.attribute(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

    private class Walk {
        val operations = ArrayList<Operation>()
        private var depth = 0

        fun children(node: Element, scope: Scope) {
            depth += 1
            if (depth > MAX_DEPTH) throw ScriptException("nested too deep")
            for (child in elements(node)) element(child, scope)
            depth -= 1
        }

        private fun push(operation: Operation) {
            if (operations.size >= MAX_OPERATIONS) throw ScriptException("too many operations")
            operations.add(operation)
        }

        private fun element(node: Element, scope: Scope) {
            when (val name = node.tagName) {
                "install", "multipass_install", "pass", "disc" -> children(node, scope)
                "archive" -> children(node, scope.with(archive = true))
                "if" -> branch(node, scope)
                "target" -> {
                    val location = node.attribute("location") ?: node.attribute("destination")
                        ?: throw ScriptException("a target without a location")
                    if (location == "user") children(node, scope.with(user = true))
                }
                // A copy of a game archive for the uninstaller.
                "source" -> {}
                "repack_into" -> repackInto(node, scope)
                "repack" -> repack(node, scope)
                "encrypt" -> encrypt(node, scope)
                "delete" -> delete(node)
                "replace", "create_folder", "install_directx", "registry_key",
                "create_shortcut", "add_remove_programs", "update_uninstall_data" -> {}
                else -> throw ScriptException("element $name")
            }
        }

        private fun branch(node: Element, scope: Scope) {
            val holds = condition(node)
            val all = elements(node)
            val branches = all.filter { it.tagName == "then" || it.tagName == "else" }
            if (branches.isEmpty()) {
                if (holds) children(node, scope)
                return
            }
            if (all.size != branches.size) throw ScriptException("an if with both branches and statements")
            val wanted = if (holds) "then" else "else"
            for (branch in branches) if (branch.tagName == wanted) children(branch, scope)
        }

        private fun repackInto(node: Element, scope: Scope) {
            if (!scope.user) return
            if (!scope.archive) throw ScriptException("files from outside the archive")
            val kind = node.attribute("type") ?: throw ScriptException("a repack_into without a type")
            val container = node.attribute("container")
            val known = (kind == "file" && container == null) || (kind == "mpq" && container != null)
            if (!known) throw ScriptException("a repack_into of another kind")
            children(node, scope.with(into = Into(kind, container)))
        }

        private fun repack(node: Element, scope: Scope) {
            val into = scope.into ?: throw ScriptException("a repack outside a repack_into")
            val from = node.attribute("from") ?: throw ScriptException("a repack without a Tome member")
            val to = plain(node.attribute("to") ?: throw ScriptException("a repack without a target"))
            val size = size(node.attribute("size"))
            val container = into.container
            push(
                if (into.kind == "mpq" && container != null) {
                    Operation.Add(plain(container), from, to, size)
                } else {
                    Operation.Extract(from, to, size)
                },
            )
        }

        private fun encrypt(node: Element, scope: Scope) {
            val into = scope.into
            val raw = into?.container
            if (into == null || into.kind != "mpq" || raw == null) throw ScriptException("an encrypt outside an archive")
            val container = plain(raw)
            val member = node.attribute("into") ?: throw ScriptException("an encrypt without a member")
            when (node.attribute("object")) {
                "cdkey26" -> push(
                    Operation.Key(
                        container,
                        member,
                        node.attribute("product_id")?.toIntOrNull() ?: throw ScriptException("a key without a product"),
                    ),
                )
                "user" -> push(Operation.Owner(container, member))
                else -> throw ScriptException("an encrypt of another object")
            }
        }

        private fun delete(node: Element) {
            if (node.attribute("type") != "file") return
            val path = node.attribute("path") ?: throw ScriptException("a delete without a path")
            // Only plain files of the game folder; anything else is not ours.
            if (!path.startsWith(ORIGINAL)) return
            val name = path.removePrefix(ORIGINAL)
            if (isPlain(name)) push(Operation.Delete(name))
        }
    }

    private const val ORIGINAL = "{OriginalInstallPath}"

    private fun condition(node: Element): Boolean {
        val whenTrue = node.attribute("true_condition")
        val whenFalse = node.attribute("false_condition")
        if ((whenTrue == null) == (whenFalse == null)) throw ScriptException("an if without one condition")
        val name = whenTrue ?: whenFalse!!
        val value = when (name) {
            "Win32", "D2MusicInstalled", "D2CharInstalled", "D2MoviesInstalled" -> true
            "MacOS", "German", "French", "Spanish", "Korean", "SimplifiedChinese",
            "TraditionalChinese", "Polish", "Italian" -> false
            else -> throw ScriptException("condition $name")
        }
        return value == (whenTrue != null)
    }

    private fun isPlain(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it in "\\/{}*?:\u0000" }

    private fun plain(name: String): String {
        if (!isPlain(name)) throw ScriptException("a name with a path")
        return name
    }

    private fun size(value: String?): Long? = when {
        value == null || value.endsWith("?") -> null
        else -> value.toLongOrNull() ?: throw ScriptException("a size that is not a number")
    }
}
