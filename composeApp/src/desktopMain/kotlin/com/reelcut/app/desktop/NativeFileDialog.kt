package com.reelcut.app.desktop

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The current Windows "Open" window (the one File Explorer uses: quick-access list, search, file-type
 * drop-down). Java's own dialog is the old Windows 7 style, so this borrows the real one from
 * PowerShell. Returns null if it can't be shown (the caller falls back to Java's dialog); an empty
 * list means the user cancelled.
 */
object NativeFileDialog {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun open(title: String, filterName: String, extensions: Collection<String>, multiple: Boolean = true): List<String>? {
        if (!isWindows) return null
        val patterns = extensions.sorted().joinToString(";") { "*.$it" }
        val filter = "$filterName|$patterns|All files|*.*"
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            Add-Type -AssemblyName System.Windows.Forms
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            ${'$'}owner = New-Object System.Windows.Forms.Form
            ${'$'}owner.TopMost = ${'$'}true
            ${'$'}owner.ShowInTaskbar = ${'$'}false
            ${'$'}owner.Opacity = 0
            ${'$'}owner.StartPosition = 'CenterScreen'
            ${'$'}owner.Show()
            ${'$'}d = New-Object System.Windows.Forms.OpenFileDialog
            ${'$'}d.Title = '${esc(title)}'
            ${'$'}d.Filter = '${esc(filter)}'
            ${'$'}d.Multiselect = ${if (multiple) "${'$'}true" else "${'$'}false"}
            ${'$'}d.CheckFileExists = ${'$'}true
            ${'$'}d.InitialDirectory = [Environment]::GetFolderPath('MyVideos')
            if (${'$'}d.ShowDialog(${'$'}owner) -eq [System.Windows.Forms.DialogResult]::OK) {
                ${'$'}d.FileNames | ForEach-Object { [Console]::Out.WriteLine(${'$'}_) }
            }
            ${'$'}owner.Dispose()
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return runCatching {
            val process = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-STA",
                "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", encoded,
            ).redirectErrorStream(false).start()
            process.errorStream.bufferedReader().let { r -> Thread { runCatching { r.readText() } }.apply { isDaemon = true }.start() }
            val lines = process.inputStream.bufferedReader(Charsets.UTF_8).readLines()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            if (finished && process.exitValue() != 0) return null
            lines.map { it.trim() }.filter { it.isNotEmpty() && File(it).isFile }
        }.getOrNull()
    }

    private fun esc(s: String) = s.replace("'", "''")
}
