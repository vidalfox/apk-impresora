# Comanda Print - version minima

App de prueba: manda una comanda de ejemplo a una impresora termica Bluetooth (ESC/POS).
No consulta Odoo todavia.

## Generar el APK con GitHub (sin instalar nada)
1. Crea un repositorio nuevo en github.com (puede ser privado).
2. Sube TODO el contenido de esta carpeta (incluida la carpeta oculta `.github`).
3. Entra a la pestana **Actions**, abre la corrida "Build APK" y espera ~3-5 minutos.
4. Al terminar, baja el archivo `comanda-print-apk` (zip) de la seccion **Artifacts**.
   Adentro esta `app-debug.apk`.

## Instalar en el celular o tablet
1. Pasa `app-debug.apk` al equipo (WhatsApp, correo, USB).
2. Abrelo y permite "instalar apps de origen desconocido" si lo pide.

## Probar
1. En Ajustes > Bluetooth del Android, EMPAREJA la impresora (PIN 0000 o 1234).
2. Abre Comanda Print, concede el permiso de Bluetooth.
3. Elige la impresora y toca "Imprimir comanda de prueba".
4. Si las tildes salen raras, cambia la "Tabla de caracteres" y repite.
   Anota cual salio bien (CP858, CP850, CP437 o WPC1252).
