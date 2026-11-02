package com.pd2updater.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstallScriptTest {
    private val script = """<?xml version="1.0" encoding="UTF-8"?>
<!-- A synthetic script in the shape of Blizzard's. -->
<install>
<if true_condition="Win32"><replace symbol="Launch" with="Game.exe" /></if>
<if true_condition="German"><then>
  <replace symbol="Name" with="Spiel" />
</then><else>
  <replace symbol="Name" with="Game" />
</else></if>
<if true_condition="Win32">
  <delete type="file" path="{OriginalInstallPath}old.dll" optional="1" />
  <delete type="file" path="{OriginalInstallPath}sub\old.dll" optional="1" />
  <delete type="recursive_folder" path="{OriginalInstallPath}support" optional="1" />
  <target location="start_menu"><delete type="recursive_folder" path="" /></target>
</if>
<if false_condition="D2MusicInstalled">
  <disc with_title="x" with_file="music.mpq" name="x">
    <target location="user"><repack_into type="file"><repack input0="music.mpq" to="music.mpq" size="1?" /></repack_into></target>
  </disc>
</if>
<target location="user"><source location="root"><repack_into type="file"><repack from="x" to="x" /></repack_into></source></target>
<disc with_title="t" with_file="Tome" name="n">
<archive origin="disc" input="#INPUT#" path="Tome">
  <if true_condition="MacOS">
    <target location="user"><repack_into type="file"><repack from="MAC\x" to="x.app" size="1" /></repack_into></target>
  </if>
  <if true_condition="Win32">
    <target location="user">
      <repack_into type="file" conflict="replace">
        <repack from="Common\data.mpq" to="data.mpq" size="1000" />
        <repack from="PC\Game.exe" to="Game.exe" size="20" />
      </repack_into>
    </target>
    <target location="user">
      <repack_into type="mpq" container="data.mpq" options="disable_compression/1">
        <repack from="PC-100\a.dll" to="a.dll" size="30" />
      </repack_into>
    </target>
    <target location="uninstall"><repack_into type="file"><repack input0="u.exe" to="u.exe" /></repack_into></target>
    <target location="user">
      <repack_into type="mpq" container="data.mpq" options="disable_compression/1">
        <encrypt object="cdkey26" into="data\global\key.wav" prompt="{P}" product_id="24" />
        <encrypt object="user" into="data\global\owner.wav" />
      </repack_into>
    </target>
  </if>
</archive>
</disc>
<target location="desktop"><create_shortcut path="Game.exe" /></target>
<target location="user"><registry_key where="user" key_name="x" value_name="y" /></target>
</install>
"""

    private fun wrap(body: String) = "<install>$body</install>"

    private fun refused(text: String) = assertThrows(ScriptException::class.java) { InstallScript.plan(text) }

    @Test
    fun aScriptBecomesItsWindowsPlan() {
        assertEquals(
            listOf(
                Operation.Delete("old.dll"),
                Operation.Extract("Common\\data.mpq", "data.mpq", 1000),
                Operation.Extract("PC\\Game.exe", "Game.exe", 20),
                Operation.Add("data.mpq", "PC-100\\a.dll", "a.dll", 30),
                Operation.Key("data.mpq", "data\\global\\key.wav", 24),
                Operation.Owner("data.mpq", "data\\global\\owner.wav"),
            ),
            InstallScript.plan(script),
        )
        assertEquals(6, InstallScript.plan("﻿$script").size)
    }

    @Test
    fun anUncertainSizeIsNoSize() {
        val text = wrap(
            """<archive><target location="user"><repack_into type="file"><repack from="a" to="x" size="12?" /></repack_into></target></archive>""",
        )
        assertEquals(listOf(Operation.Extract("a", "x", null)), InstallScript.plan(text))
    }

    @Test
    fun anythingUnknownFailsTheRead() {
        assertEquals("element launch_game", refused(wrap("<launch_game />")).message)
        assertEquals("condition Linux", refused(wrap("""<if true_condition="Linux"></if>""")).message)
        refused(wrap("""<archive><target location="user"><repack_into type="file"><repack from="a" to="..\x" /></repack_into></target></archive>"""))
        refused(wrap("""<target location="user"><repack_into type="file"><repack from="a" to="x" /></repack_into></target>"""))
        refused(wrap("""<archive><target location="user"><repack_into type="mpq" container="d.mpq"><encrypt object="usbkey26" into="k" /></repack_into></target></archive>"""))
        assertEquals("xml", refused("<install").message)
        assertEquals("element other", refused("<other />").message)
    }

    @Test
    fun limitsAreEnforced() {
        val many = wrap("""<delete type="file" path="{OriginalInstallPath}a" />""".repeat(600))
        assertEquals("too many operations", refused(many).message)
        fun nested(levels: Int) = wrap("""<if true_condition="Win32">""".repeat(levels) + "</if>".repeat(levels))
        assertEquals("nested too deep", refused(nested(100)).message)
        assertEquals(emptyList<Operation>(), InstallScript.plan(nested(10)))
    }
}
