package com.pd2updater

import java.io.File

/**
 * The renderer PD2's Game.exe starts with, as the desktop PD2 Launcher passes it
 * (pd2-launcher src/process.rs): `-3dfx` for Glide (D2GL), `-ddraw` for DirectDraw (cnc-ddraw).
 */
enum class Renderer(val argument: String, val label: String) {
    GLIDE("-3dfx", "3D (Glide)"),
    DDRAW("-ddraw", "DirectDraw"),
    ;

    companion object {
        fun of(name: String?): Renderer = entries.firstOrNull { it.name == name } ?: GLIDE
    }
}

/**
 * Winlator starts the game, not this app, so the renderer goes into a small batch file in the
 * ProjectD2 folder that a Winlator shortcut runs.
 */
object GameLauncher {
    const val FILE_NAME = "PD2.bat"

    fun script(renderer: Renderer): String {
        val lines = mutableListOf(
            "@echo off",
            "rem Written by PD2 Updater. Renderer: ${renderer.label}",
            "cd /d \"%~dp0\"",
        )
        if (renderer == Renderer.DDRAW) {
            // PD2's own ddraw.dll, never Wine's builtin one (the desktop launcher does the same).
            lines += "set WINEDLLOVERRIDES=ddraw=n,b"
        }
        lines += "start \"\" \"Game.exe\" ${renderer.argument}"
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    fun write(projectD2: File, renderer: Renderer): File {
        val file = File(projectD2, FILE_NAME)
        file.writeText(script(renderer), Charsets.US_ASCII)
        return file
    }
}
