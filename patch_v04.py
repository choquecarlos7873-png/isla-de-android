from pathlib import Path
root=Path("SubtitulosES")
p=root/"app/src/main/java/bo/subtitulos/es/SubtitleService.kt"
s=p.read_text()
def rep(old,new):
    global s
    assert old in s, "No se encuentra: "+old[:85]
    s=s.replace(old,new)
rep("private const val CAPTURE_INTERVAL = 550L","private const val CAPTURE_INTERVAL = 260L")
rep("private var blankCount = 0","private var lastTextAt = 0L")
rep("maxLines = 4","maxLines = 10")
start=s.index("            val top = (image.height * topFraction)")
end=s.index("            full.recycle()",start)
s=s[:start]+"            cropped = Bitmap.createBitmap(full, 0, 0, image.width, image.height)\n"+s[end:]
start=s.index("                        val raw = result.textBlocks.flatMap { it.lines }")
end=s.index("                        handleSubtitle(raw)",start)
s=s[:start]+'''                        val raw = result.textBlocks.flatMap { it.lines }
                            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                            .map { it.text.replace(Regex("\\\\s+"), " ").trim() }
                            .filter { englishLikely(it) }
                            .take(22)
                            .joinToString("\\n")
                            .take(850).trim()
'''+s[end:]
start=s.index("    private fun handleSubtitle(raw: String)")
s=s[:start]+'''    private val englishWords = setOf("the","and","a","an","is","are","was","were","it","you",
        "your","we","they","what","why","when","where","how","with","this","that","there",
        "have","has","had","for","from","to","of","on","in","at","not","but","or","if",
        "will","would","could","should","do","does","did","can","just","like","my","me",
        "no","yes","know","think","want","need","see","say","said","get","got","don't",
        "it's","i'm","you're","now","more","some","about","all")
    private val spanishWords = setOf("que","de","del","el","la","los","las","por","para","con",
        "una","uno","este","esta","como","pero","sin","donde","porque","ahora","gracias","quiero")
    private fun englishLikely(text:String):Boolean {
        if (text.count { it.isLetter() } < 2 || text.any { it in "áéíóúñ¿¡ÁÉÍÓÚÑ" }) return false
        val words=Regex("[A-Za-z]+(?:'[A-Za-z]+)?").findAll(text).map{it.value.lowercase()}.toList()
        if (words.isEmpty()) return false
        val english=words.count{it in englishWords}
        val spanish=words.count{it in spanishWords}
        return (english>spanish && english>0) || (english==0 && spanish==0 &&
            words.size>=3 && words.count{it.length>3}>=2)
    }

'''+s[start:]
rep('''            blankCount++
            if (blankCount >= 4 && latestSource.isNotBlank()) {''','''            if (SystemClock.elapsedRealtime() - lastTextAt >= 2200L && latestSource.isNotBlank()) {''')
rep('''        blankCount = 0
        if (raw.length > 260 || raw == latestSource) return''','''        lastTextAt = SystemClock.elapsedRealtime()
        if (raw == latestSource) return''')
rep('''            view.text = text
            view.visibility = if (text.isBlank()) View.INVISIBLE else View.VISIBLE''','''            if (text.isNotBlank()) {
                if (view.text.toString() != text) view.text = text
                if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
            } else if (view.visibility != View.INVISIBLE) {
                view.visibility = View.INVISIBLE
            }''')
rep('''                        getSharedPreferences("subtitulos", MODE_PRIVATE).edit()
                            .putInt("caption_x", (xFraction * 100).toInt().coerceIn(0, 90))
                            .putInt("caption_y", (yFraction * 100).toInt().coerceIn(0, 92))''','''                        val key = if (screenWidth > screenHeight) "horizontal" else "vertical"
                        getSharedPreferences("subtitulos", MODE_PRIVATE).edit()
                            .putInt("${key}_x", (xFraction * 100).toInt().coerceIn(0, 90))
                            .putInt("${key}_y", (yFraction * 100).toInt().coerceIn(0, 92))''')
rep('''            view.maxWidth = (screenWidth * .90f).toInt().coerceAtLeast(dp(160))
            p.x''','''            val landscape = screenWidth > screenHeight
            val key = if (landscape) "horizontal" else "vertical"
            val prefs = getSharedPreferences("subtitulos", MODE_PRIVATE)
            xFraction = prefs.getInt("${key}_x", if (landscape) 10 else 6) / 100f
            yFraction = prefs.getInt("${key}_y", if (landscape) 69 else 75) / 100f
            view.textSize = if (landscape) 18f else 20f
            view.maxLines = if (landscape) 7 else 10
            view.maxWidth = (screenWidth * if (landscape) .80f else .90f).toInt().coerceAtLeast(dp(160))
            p.x''')
rep('''    private var translationFailed = false''','''    private var translationFailed = false
    private var displayManager: DisplayManager? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY || stopping) return
            main.postDelayed({
                if (!stopping) {
                    val bounds = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else {
                        val point = android.graphics.Point()
                        @Suppress("DEPRECATION")
                        wm.defaultDisplay.getRealSize(point)
                        Rect(0,0,point.x,point.y)
                    }
                    captureHandler?.post { resizeCapture(bounds.width(), bounds.height()) }
                }
            }, 180L)
        }
    }''')
rep('''        createOverlays()
        startLedBlink()''','''        createOverlays()
        startLedBlink()
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, main)''')
rep('''        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) { }''','''        try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Exception) { }
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) { }''')
rep('''        screenWidth = width
        screenHeight = height
        positionOverlay()
        try {
            reader?.setOnImageAvailableListener(null, null)''','''        val orientationChanged = (screenWidth > screenHeight) != (width > height)
        screenWidth = width
        screenHeight = height
        if (orientationChanged) positionOverlay()
        try {
            reader?.setOnImageAvailableListener(null, null)''')
p.write_text(s)
p=root/"app/src/main/java/bo/subtitulos/es/MainActivity.kt"
s=p.read_text().replace("Subtitulos ES 0.3","Subtitulos ES 0.4")
i=s.index('        root.addView(label("1. Elige la zona')
j=s.index("        startButton = button(",i)
s=s[:i]+'''        root.addView(label("1. Lee toda la pantalla y busca ingles, sin elegir una franja."))
        root.addView(label("2. Arrastra el subtitulo al lugar que prefieras, tanto en vertical como horizontal. Las posiciones se guardan por separado."))
        root.addView(label("3. El texto cambia sin animacion ni parpadeos: solo el punto indicador parpadea. La traduccion puede tardar segun el dispositivo."))
'''+s[j:]
s=s.replace("Solo se mostraran los subtitulos traducidos al espanol sobre YouTube. Sin audio, sin mensajes que tapen el video.","Traduce el texto ingles detectado en toda la pantalla, incluso girando el celular. No traduce audio.")
p.write_text(s)
p=root/"app/build.gradle.kts"
p.write_text(p.read_text().replace("versionCode = 3","versionCode = 4").replace('versionName = "0.3-beta"','versionName = "0.4-beta"'))
print("Version 0.4 aplicada correctamente")
