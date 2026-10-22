# Isla de Android

Primera prueba funcional (0.2.0) de una isla de cámara puramente visual para Android. Kotlin y vistas nativas, Android 8.0 o superior.

## Qué hace

- Dibuja una cápsula negra `#000000`, totalmente opaca, encima de otras aplicaciones.
- Continúa al salir al inicio o cerrar la pantalla de ajustes, mediante un servicio foreground con notificación y acción **Detener**.
- Guarda largo, grosor, desplazamiento y separación del borde por separado para vertical y horizontal. Los cambios se aplican en vivo.
- Sigue el recorte de cámara que Android informe. Sin datos del recorte, usa el centro del borde superior nativo, transformado según las cuatro rotaciones.
- Permite desactivar la alineación automática y calibrar manualmente. No abre la cámara ni inspecciona otras apps.

## Primera prueba en un teléfono

1. Instala el APK de depuración.
2. Abre **Isla de Android** y pulsa **Permitir superposición**. Autoriza **Mostrar sobre otras aplicaciones** en Ajustes y regresa.
3. Permite notificaciones si quieres el botón **Detener** en la notificación (recomendado, no obligatorio).
4. Pulsa **Continuar a mi isla** y después **Activar isla**.
5. Vuelve al inicio o abre otra app. Prueba ambos sentidos de horizontal con la rotación del teléfono habilitada.
6. Ajusta los dos perfiles dentro de la app. Si el recorte de cámara no coincide, desactiva **Alinear con la cámara** y calibra los controles.
7. Para quitarla, pulsa **Desactivar isla** o **Detener** en la notificación.

## Permisos

| Permiso | Motivo |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | Dibujar fuera de la app; se autoriza desde Ajustes. |
| `FOREGROUND_SERVICE` | Mantener la isla activa con servicio visible. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Tipo de servicio visual persistente en Android 14+. |
| `POST_NOTIFICATIONS` | Aviso y acción Detener en Android 13+; opcional. |

No solicita cámara, micrófono, ubicación, accesibilidad, Internet ni lectura de notificaciones. No inicia después de reiniciar el teléfono: abre la app y vuelve a activarla. No solicita exclusión automática de ahorro de batería; algunos fabricantes requieren permitir actividad en segundo plano en Ajustes.

## Límites de Android

La superposición queda por debajo de ventanas críticas como la barra de estado y el teclado. Android o una app protegida pueden ocultarla, y algunos fabricantes restringen el área del recorte. No se garantiza visibilidad en bloqueo, pantallas de permisos ni menús protegidos. El sistema puede detener el servicio por batería, memoria, reinicio o una acción del usuario.

El color es negro opaco. La ventana usa `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`: su pequeño rectángulo no tiene acciones y no deja pasar toques; el resto de la pantalla queda fuera de la ventana. Se evita `FLAG_NOT_TOUCHABLE`, cuya opacidad algunos sistemas limitan a 0.8, porque la prueba en el Infinix produjo gris en lugar de negro puro. No se crea una capa de pantalla completa ni se captura el foco del teclado.

La posición automática usa `DisplayCutout`, no reconocimiento de imagen. En móviles que no informen el recorte, tablets con orientación natural horizontal o algunos plegables, puede necesitar ajuste manual. La vista previa es orientativa y no sustituye la calibración en el teléfono.

Documentación: [ventanas de superposición](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY), [recortes de pantalla](https://developer.android.com/develop/ui/views/layout/display-cutout), [servicios specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

## Abrir en Android Studio

1. Abre esta carpeta con **File > Open**.
2. Usa el JDK incluido en Android Studio para Gradle (Java 17 o superior).
3. Instala Android SDK Platform 37 desde SDK Manager si se solicita.
4. Espera la sincronización de Gradle, selecciona un emulador o teléfono con Android 8.0 o superior y pulsa **Run**.

## Compilar

Windows: `gradlew.bat assembleDebug lintDebug testDebugUnitTest`

macOS/Linux: `./gradlew assembleDebug lintDebug testDebugUnitTest`

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Pruebas

Las pruebas unitarias cubren cuatro rotaciones, recortes asimétricos, densidad, ajustes manuales y límites de pantalla.

El runner `IslandSmokeInstrumentation` es una prueba de integración opcional para un dispositivo de prueba autorizado, con el permiso de superposición concedido por su propietario. Enciende y apaga la isla, comprueba píxeles negros, ajustes en vivo, rotación y persistencia del servicio al salir de la actividad y al abrir el panel de notificaciones. Guarda un informe en `files/smoke-report.txt` de la app y capturas solo mientras nuestra actividad tiene el foco. Restaura perfiles y ajustes de rotación y deja la isla apagada. No concede ni revoca permisos.

Para ejecutarlo, selecciona explícitamente el dispositivo de prueba (`ANDROID_SERIAL`), instala los APK principal y de `assembleDebugAndroidTest`, y ejecuta `adb -s <serial> shell am instrument -w com.isladeandroid.app.test/com.isladeandroid.app.IslandSmokeInstrumentation`. No debe ejecutarse en teléfonos ajenos ni como parte de una compilación normal.

## Estructura

- `app/src/main/java/com/isladeandroid/app/MainActivity.kt`: permisos y controles.
- `app/src/main/java/com/isladeandroid/app/IslandOverlayService.kt`: servicio y ventana global.
- `app/src/main/java/com/isladeandroid/app/IslandGeometry.kt`: posicionamiento y rotaciones.
- `app/src/main/java/com/isladeandroid/app/IslandPreviewView.kt`: vista previa de ejemplo.
- `app/src/test/java/com/isladeandroid/app/IslandGeometryTest.kt`: pruebas de cámara, rotaciones y límites.
- `app/src/androidTest/java/com/isladeandroid/app/IslandSmokeInstrumentation.kt`: prueba de integración opcional en dispositivo.
- `app/src/main/res/values/strings.xml`: textos y nombre visible.
- `app/src/main/res/drawable/ic_island.xml`: icono vectorial.

El identificador es `com.isladeandroid.app`. La versión de depuración sirve para instalar y probar, no está firmada para publicación en Google Play. La distribución en Play requeriría declarar y justificar el uso del servicio `specialUse` ante su revisión.

Configuración: Android Gradle Plugin 9.4.1, Gradle 9.6.0, SDK 37, Android mínimo 26. No se incluyen claves de firma ni archivos específicos de la máquina.
