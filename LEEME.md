# Rótulo: subtítulos automáticos traducidos

Rótulo escucha el video, escribe lo que se dice y lo traduce al español. También permite agregar
títulos y stickers y exportar el video con todo incluido. El proyecto trae dos versiones:

- **App Android** (esta carpeta): se compila como APK.
- **Versión web** (carpeta `docs/`): se publica gratis con GitHub Pages y se abre desde el
  navegador del celular, que permite instalarla como app.

## Cómo funcionan los subtítulos
1. Se extrae el audio del video y se divide en partes de hasta 29 segundos, cortando en los silencios.
2. **Whisper** (modelo de voz de OpenAI, versión ONNX) escucha cada parte:
   - si el video está en inglés o en español, lo transcribe;
   - si está en otro idioma, lo traduce al inglés.
3. **Opus-MT en→es** (Helsinki-NLP) traduce del inglés al español.
4. Revisas y corriges los subtítulos, ajustas la sincronía y el estilo, y exportas el video o el archivo `.srt`.

Todo corre en el teléfono mediante Transformers.js 3.8.1, con WebAssembly. El motor viene incluido en la app (carpeta `vendor/`); solo los modelos se descargan. La primera vez se descargan
los modelos desde Hugging Face: aproximadamente 150 MB en precisión «Rápida» (whisper-base) y
350 MB en «Precisa» (whisper-small). Luego quedan guardados en el dispositivo.

**Detección de idioma:** Transformers.js no la trae para Whisper, así que Rótulo la calcula leyendo
el primer paso del modelo. Si se equivoca, elige el idioma a mano y genera de nuevo.

**Calidad:** en idiomas distintos al inglés la traducción pasa por el inglés (dos pasos), así que
conviene revisarla. «Precisa» entiende mejor, pero es más lenta y pesada; en celulares con poca
memoria usa «Rápida».

## Películas y videos largos
- La app Android lee el audio **por partes**, directamente del archivo, así que no carga la película en memoria.
- Puede trabajar **en segundo plano**: sales de la app o apagas la pantalla y sigue, con una notificación que muestra el avance.
  Si el teléfono la detiene, toca «Permitir sin restricciones de batería» en la pestaña Subtítulos.
- Los subtítulos aparecen a medida que se generan. **Pausar** guarda el avance y **Continuar** retoma desde ahí, incluso si cierras la app.
- Para ver una película, lo práctico es **Descargar .srt** (queda con el mismo nombre del video en Descargas/Rotulo)
  y abrirlo con VLC o MX Player. Quemar los subtítulos en el video tarda lo mismo que dura la película.
- En el navegador (GitHub Pages) el límite es 30 minutos por video, porque allí sí se carga el audio completo.
- Velocidad: depende del procesador. El motor usa varios núcleos cuando el sistema lo permite;
  la pestaña Subtítulos muestra la velocidad real y el tiempo restante estimado.

## Opción A: todo desde el celular con GitHub (gratis)
1. Crea una cuenta en github.com y un repositorio nuevo, por ejemplo `rotulo` (público).
2. Sube **todo** el contenido de esta carpeta con *Add file → Upload files*, conservando las carpetas
   (`app/`, `docs/`, `.github/`...).
3. **APK:** pestaña **Actions → Compilar APK → Run workflow**. Al terminar, unos 5 minutos, abre la
   ejecución y descarga **Rotulo-apk** (un .zip con el .apk). Instálalo y acepta "instalar apps de origen desconocido".
4. **Versión web:** *Settings → Pages → Branch: main, carpeta /docs → Save*. En un par de minutos queda
   en `https://TU-USUARIO.github.io/rotulo/`. Ábrela en Chrome del celular y usa *Agregar a la pantalla principal*.

## Opción B: Android Studio
*File → Open* esta carpeta. Si pide el Gradle Wrapper, acepta que lo genere (o ejecuta `gradle wrapper`).
Luego *Build → Build APK(s)*.

## Qué hace la parte nativa (Android)
- Abre el selector de videos del teléfono.
- Guarda el video exportado en **Galería → Movies/Rotulo** y los `.srt` en **Descargas/Rotulo**
  (Android 10 o superior, sin pedir permisos).
- Comparte el resultado (WhatsApp, Drive, etc.).
- Aparece en el menú **Compartir** de la Galería: compartes un video "con Rótulo" y se abre listo.
- Mantiene la pantalla encendida mientras genera subtítulos o exporta.

## Actualizar
El editor es un solo archivo, que está en dos lugares: `app/src/main/assets/www/index.html` (app)
y `docs/index.html` (web). Si lo cambias, actualiza los dos.

## Versiones
Android Gradle Plugin 8.5.2 · Kotlin 1.9.24 · Gradle 8.9 · compileSdk/targetSdk 34 · minSdk 29 ·
Transformers.js 3.8.1 · onnx-community/whisper-base y whisper-small · Xenova/opus-mt-en-es.
Para publicar en Google Play hay que subir targetSdk al mínimo que exija Play en ese momento y firmar
una versión *release*.

## Licencias de los modelos
Whisper es de OpenAI y Opus-MT de Helsinki-NLP. Antes de distribuir la app públicamente, revisa la licencia de cada modelo en su página de Hugging Face.
