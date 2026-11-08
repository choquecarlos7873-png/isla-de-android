from pathlib import Path

root = Path("SubtitulosES")
service = root / "app/src/main/java/bo/subtitulos/es/SubtitleService.kt"
s = service.read_text()

def change(old, new):
    global s
    if old not in s:
        raise RuntimeError("No encuentro el fragmento requerido: " + old[:80])
    s = s.replace(old,new)

change('import android.widget.TextView',
       'import android.widget.TextView\nimport android.widget.Toast')
change('    private var captionLayout: WindowManager.LayoutParams? = null',
       '''    private var captionLayout: WindowManager.LayoutParams? = null
    private var diagnosis: TextView? = null
    private var frameCounter = 0
    private var emptyCounter = 0
    private var lastProgress = 0L
''')
change('        setCaption("Preparando traducción español…")',
       '''        setCaption("PRUEBA DE VENTANA: ESPAÑOL")
        setDiagnosis("Permiso de pantalla concedido. Inicializando...")
        main.postDelayed({
            if (!stopping && frameCounter == 0) setDiagnosis("SIN CAPTURAS: verifica permiso de pantalla completa")
        }, 9000L)''')
change('''        translator.downloadModelIfNeeded()
            .addOnSuccessListener''',
       '''        setDiagnosis("Descargando modelo inglés → español (requiere Internet)...")
        translator.downloadModelIfNeeded()
            .addOnSuccessListener''')
change('''                    ready = true
                    setCaption("Esperando subtítulos en inglés…")''',
       '''                    ready = true
                    lastRecognized = ""
                    setCaption("Esperando subtítulos en inglés…")
                    setDiagnosis("Modelo descargado: buscando subtítulos...")''')
change('''                if (!stopping) setCaption("No se pudo descargar el modelo. Comprueba Internet y reinicia.")''',
       '''                if (!stopping) {
                    setCaption("No se descargó el modelo de traducción. Activa Internet")
                    setDiagnosis("Error de modelo: " + it.javaClass.simpleName + " · " + it.message)
                }''')
change('''        } catch (_: Exception) {
            caption = null
            captionLayout = null
        }
    }

    private fun setCaption''',
       '''        } catch (e: Exception) {
            caption = null
            captionLayout = null
            Toast.makeText(this, "Ventana flotante bloqueada: " + e.message, Toast.LENGTH_LONG).show()
        }

        diagnosis = TextView(this).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            maxLines = 3
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(0xE0104565.toInt())
            text = "DIAGNÓSTICO: iniciando"
        }
        try {
            wm.addView(diagnosis, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(35)
            })
        } catch (e: Exception) {
            diagnosis = null
            Toast.makeText(this, "Diagnóstico flotante bloqueado: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun setDiagnosis(message: String) {
        main.post {
            if (!stopping) diagnosis?.text = "Subtítulos ES · " + message
        }
    }

    private fun setCaption''')
change('''        if (stopping || !ready || now - lastFrameAt < CAPTURE_INTERVAL_MS || !working.compareAndSet(false, true)) {''',
       '''        if (stopping || now - lastFrameAt < CAPTURE_INTERVAL_MS || !working.compareAndSet(false, true)) {''')
change('''        lastFrameAt = now
        var cropped: Bitmap? = null''',
       '''        lastFrameAt = now
        frameCounter++
        if (now - lastProgress > 2500L) {
            lastProgress = now
            setDiagnosis("Capturas: " + frameCounter +
                if (ready) " · buscando letras en zona " + (topFraction*100).toInt() + "%–" + ((topFraction+heightFraction)*100).toInt() + "%"
                else " · esperando modelo de traducción")
        }
        var cropped: Bitmap? = null''')
change('''        } catch (_: Exception) {
            working.set(false)
            cropped?.recycle()
            image.close()
            return
        }
        image.close()''',
       '''        } catch (e: Exception) {
            working.set(false)
            cropped?.recycle()
            image.close()
            setDiagnosis("No se pudo leer imagen: " + e.javaClass.simpleName + ": " + e.message)
            return
        }
        image.close()''')
change('''                .addOnFailureListener { if (!stopping) setCaption("No se pudo leer el texto") }''',
       '''                .addOnFailureListener { if (!stopping) setDiagnosis("Error OCR: " + it.javaClass.simpleName + " " + it.message) }''')
change('''        if (raw.length < 3) {
            emptyFrames++''',
       '''        if (raw.length < 3) {
            emptyFrames++
            if (emptyFrames == 6) setDiagnosis("Captura activa, PERO no detecta letras. Ajusta el porcentaje de lectura")''')
change('''        emptyFrames = 0
        if (raw == lastRecognized) return
        lastRecognized = raw
        translator.translate(raw)''',
       '''        emptyFrames = 0
        if (!ready) {
            setDiagnosis("OCR sí lee: " + raw.take(80) + " · esperando el traductor")
            return
        }
        if (raw == lastRecognized) return
        lastRecognized = raw
        setDiagnosis("OCR detectó: " + raw.take(80))
        translator.translate(raw)''')
change('''                if (!stopping && raw == lastRecognized) setCaption("No se pudo traducir esta frase")''',
       '''                if (!stopping && raw == lastRecognized) {
                    setCaption("No se pudo traducir esta frase")
                    setDiagnosis("Error de traducción: " + it.javaClass.simpleName + " " + it.message)
                }''')
change('''        try { caption?.let { wm.removeViewImmediate(it) } } catch (_: Exception) { }
        caption = null''',
       '''        try { caption?.let { wm.removeViewImmediate(it) } } catch (_: Exception) { }
        try { diagnosis?.let { wm.removeViewImmediate(it) } } catch (_: Exception) { }
        diagnosis = null
        caption = null''')
change('''            params.y = (screenHeight * (topFraction - .13f).coerceAtLeast(0f)).toInt()''',
       '''            params.y = (screenHeight * topFraction).toInt()''')
service.write_text(s)
gradle = root / "app/build.gradle.kts"
t = gradle.read_text().replace('versionCode = 1', 'versionCode = 2').replace('versionName = "0.1-beta"', 'versionName = "0.2-beta"')
gradle.write_text(t)
main = root / "app/src/main/java/bo/subtitulos/es/MainActivity.kt"
t = main.read_text()
needle = '        root.addView(label("Para el primer intento, usa el video en pantalla completa. Si no detecta subtítulos, vuelve aquí y ajusta la zona."))'
assert needle in t
t = t.replace(needle, '''        root.addView(label("NUEVO v0.2: una banda azul arriba te indica si Android permite capturar pantalla, si OCR lee letras y si el traductor descargó su modelo."))
        root.addView(label("Si dice 'Captura activa pero no detecta letras', ajusta la zona de lectura. Debes ver 'PRUEBA DE VENTANA' al principio."))
        root.addView(label("Usa YouTube con subtítulos ingleses visibles y conexión a Internet la primera vez."))''')
main.write_text(t)
print("Correcciones v0.2 aplicadas")
