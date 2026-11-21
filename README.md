# Flow

Editor de video para Android, pensado para editar rápido con una mano en vertical y con detalle en horizontal.

## Funciones

- **Proyectos**: borradores con miniatura, duración y tamaño; duplicar, renombrar, eliminar y limpiar caché. Autoguardado continuo y edición no destructiva.
- **Línea de tiempo multipista**: cabezal fijo al centro, zoom con pellizco, miniaturas y ondas de audio, recorte por bordes con imán y vibración, reordenar con pulsación larga, marcadores.
- **Clips**: dividir, recortar, duplicar, eliminar, reemplazar, congelar fotograma, velocidad constante y curvas de velocidad, transformar, recortar encuadre y opacidad, con manipulación directa sobre la vista previa.
- **Lienzo**: 9:16, 16:9, 1:1, 4:5, 21:9 o automático según el primer clip; fondo de color, degradado, imagen o desenfoque.
- **Color**: filtros por categoría, ajustes manuales, curvas, HSL, LUT `.cube` y capas de ajuste.
- **Texto y subtítulos**: estilos, fuentes propias `.ttf`/`.otf`, animaciones, plantillas e importación/exportación `.srt`.
- **Audio**: música y sonidos, extracción del audio de un video y voz en off con cuenta regresiva.
- **Exportación**: 480p a 4K, 24 a 60 fps, calidad y códec H.264/H.265, peso estimado, progreso en segundo plano y compartir.

## Arquitectura

| Módulo | Contenido |
|---|---|
| `core:model` | Documento del proyecto inmutable y operaciones de edición puras (JVM, con pruebas). |
| `core:data` | Almacenamiento JSON atómico, índice en Room, autoguardado y fuentes importadas. |
| `core:engine` | Media3: análisis de medios, miniaturas, ondas, composición, vista previa y exportación. |
| `core:audio` | Grabación de voz en off a WAV. |
| `core:designsystem` | Tema oscuro y componentes compartidos. |
| `feature:home` / `feature:editor` / `feature:export` | Pantallas en Jetpack Compose. |

## Compilar

Requiere Android Studio reciente (AGP 9.5, JDK 17+) y el SDK 37.1.

```bash
./gradlew assembleDebug
./gradlew :core:model:test :core:audio:testDebugUnitTest :core:engine:testDebugUnitTest
```

## Licencia

Copyright (C) 2026 Oscar Reyes

Flow es software libre: puedes redistribuirlo y modificarlo bajo los términos de la
[Licencia Pública General de GNU versión 3](LICENSE) (GPL-3.0-or-later) publicada por la
Free Software Foundation. Se distribuye con la esperanza de que sea útil, pero **sin
ninguna garantía**; consulta el archivo `LICENSE` para más detalles.

Las bibliotecas de terceros conservan sus propias licencias. La exportación a MP3 usa
[LAME](https://lame.sourceforge.io/), distribuido bajo la LGPL como biblioteca
compartida independiente.
