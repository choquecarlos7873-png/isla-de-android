# Isla de Android

Proyecto inicial de una aplicación Android nativa en Kotlin, con interfaz XML.

## Abrir en Android Studio

1. Abre esta carpeta con **File > Open**.
2. Usa el JDK incluido en Android Studio para Gradle (Java 17 o superior).
3. Instala Android SDK Platform 37 desde SDK Manager si se solicita.
4. Espera la sincronización de Gradle, selecciona un emulador o teléfono con Android 8.0 o superior y pulsa **Run**.

## Compilar

Windows: `gradlew.bat assembleDebug lintDebug`

macOS/Linux: `./gradlew assembleDebug lintDebug`

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Estructura

- `app/src/main/java/com/isladeandroid/app/MainActivity.kt`: primera pantalla y acción del botón.
- `app/src/main/res/layout/activity_main.xml`: diseño de bienvenida.
- `app/src/main/res/values/strings.xml`: textos y nombre visible.
- `app/src/main/res/drawable/ic_island.xml`: icono vectorial.

La base incluye bienvenida y un botón informativo. Todavía no incluye cuentas, servidor ni funciones de negocio. El identificador inicial es `com.isladeandroid.app`; conviene decidir el definitivo antes de publicar en Google Play.

Configuración: Android Gradle Plugin 9.4.1, Gradle 9.6.0, SDK 37, Android mínimo 26. No se incluyen claves de firma ni archivos específicos de la máquina.
