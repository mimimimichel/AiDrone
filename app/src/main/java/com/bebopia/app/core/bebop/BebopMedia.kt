package com.bebopia.app.core.bebop

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Photos et vidéos du Bebop, copiées par son serveur FTP (192.168.42.1:21, dossier /internal_000/Bebop_2/media).
 * Les noms contiennent la date : Bebop_2_20261021T143000+0200_XXXX.mp4 / .jpg.
 */
class BebopMedia(private val host: String, private val binder: SocketBinder) {
    private fun socket(port: Int): Socket = Socket().also { binder.bind(it); it.connect(InetSocketAddress(host, port), 5000); it.soTimeout = 20_000 }

    private class Ftp(val s: Socket) {
        val inp = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        val out = s.getOutputStream()
        fun reply(): String {
            var line = inp.readLine() ?: throw IllegalStateException("FTP fermé")
            val code = line.take(3)
            if (line.length > 3 && line[3] == '-') while (true) { line = inp.readLine() ?: break; if (line.startsWith("$code ")) break }
            return line
        }
        fun cmd(c: String): String { out.write("$c\r\n".toByteArray(Charsets.ISO_8859_1)); out.flush(); return reply() }
    }

    /** Date de prise de vue d'après le nom du fichier, null si illisible. */
    fun timeOf(name: String): Long? = Regex("(\\d{8}T\\d{6})").find(name)?.groupValues?.get(1)?.let {
        runCatching { SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT).parse(it)?.time }.getOrNull()
    }

    fun download(sinceMs: Long, videos: Boolean, dir: File, progress: (String) -> Unit): String {
        dir.mkdirs()
        val ftp = Ftp(socket(21))
        try {
            ftp.reply()
            ftp.cmd("USER anonymous")
            ftp.cmd("PASS bebopia")
            ftp.cmd("TYPE I")
            if (!ftp.cmd("CWD /internal_000/Bebop_2/media").startsWith("2")) return "dossier des médias introuvable sur le Bebop"
            val names = list(ftp, "NLST")
            val wanted = names.filter { n ->
                val ok = n.endsWith(".jpg", true) || (videos && n.endsWith(".mp4", true))
                ok && (timeOf(n)?.let { it >= sinceMs - 60_000 } ?: false)
            }
            if (wanted.isEmpty()) return "aucune photo ni vidéo de ce vol sur le Bebop"
            var n = 0
            for ((i, name) in wanted.withIndex()) {
                progress("📥 ${i + 1}/${wanted.size} : $name")
                val data = passive(ftp)
                val r = ftp.cmd("RETR $name")
                if (!r.startsWith("1")) { data.close(); continue }
                data.getInputStream().use { inp -> File(dir, name).outputStream().use { inp.copyTo(it) } }
                data.close()
                ftp.reply()
                n++
            }
            return "$n fichier(s) copié(s) sur le téléphone"
        } finally {
            runCatching { ftp.cmd("QUIT") }
            ftp.s.close()
        }
    }

    private fun passive(ftp: Ftp): Socket {
        val r = ftp.cmd("PASV")
        val nums = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(r)?.groupValues?.drop(1)?.map { it.toInt() }
            ?: throw IllegalStateException("réponse PASV illisible : $r")
        return socket(nums[4] * 256 + nums[5])
    }

    private fun list(ftp: Ftp, cmd: String): List<String> {
        val data = passive(ftp)
        ftp.cmd(cmd)
        val names = data.getInputStream().bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        data.close()
        ftp.reply()
        return names
    }
}
