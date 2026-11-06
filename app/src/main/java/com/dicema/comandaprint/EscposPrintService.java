package com.dicema.comandaprint;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import android.print.PrintAttributes;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Servicio de impresión de Android: aparece como una impresora más en el diálogo
 * "Imprimir" de Chrome y otras apps. Recibe el PDF, lo convierte a imagen en
 * blanco y negro (raster ESC/POS) y lo envía a la térmica por USB.
 */
public class EscposPrintService extends PrintService {

    static final String PREFS = "comandaprint";
    static final String KEY_DOTS = "ancho_puntos";   // 384 (58 mm) o 576 (80 mm)

    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.add(this, "Servicio creado");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            String st = android.util.Log.getStackTraceString(e);
            if (st.length() > 700) st = st.substring(0, 700);
            Logger.add(getApplicationContext(), "CIERRE INESPERADO: " + st);
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    @Override
    protected void onConnected() {
        Logger.add(this, "Android se conectó al servicio");
    }

    @Override
    protected void onDisconnected() {
        Logger.add(this, "Android se desconectó del servicio");
    }

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        Logger.add(this, "Android pide crear sesión de descubrimiento");
        session = new Session();
        return session;
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob job) {
        job.cancel();
    }

    @Override
    protected void onPrintJobQueued(final PrintJob job) {
        final Context ctx = getApplicationContext();
        Logger.add(ctx, "Trabajo recibido");
        try {
            job.start();
        } catch (Throwable t) {
            Logger.add(ctx, "Error en job.start: " + t);
        }
        // El documento SOLO se puede leer desde el hilo principal
        ParcelFileDescriptor data = null;
        String earlyError = null;
        try {
            data = job.getDocument().getData();
        } catch (Throwable t) {
            earlyError = "No se pudo leer el documento: " + t;
        }
        final ParcelFileDescriptor pdfData = data;
        final String earlyErr = earlyError;

        new Thread(() -> {
            String err;
            try {
                err = earlyErr != null ? earlyErr : doPrint(ctx, pdfData);
            } catch (Throwable t) {
                err = "Fallo inesperado: " + t;
            }
            final String error = err;
            Logger.add(ctx, error == null ? "Impreso OK" : "Error: " + error);
            main.post(() -> {
                try {
                    if (error == null) job.complete();
                    else job.fail(error);
                    // Que Android no se quede con un estado viejo de la impresora
                    if (session != null) session.announce("tras trabajo");
                } catch (Throwable t) {
                    Logger.add(ctx, "Error cerrando trabajo: " + t);
                }
            });
        }).start();
    }

    // ------------------------------------------------------------ Impresión

    private String doPrint(Context ctx, ParcelFileDescriptor src) {
        File tmp = null;
        PdfRenderer renderer = null;
        ParcelFileDescriptor pfd = null;
        try {
            UsbDevice dev = UsbPrinter.findFirst(ctx);
            for (int t = 0; dev == null && t < 16; t++) { // hasta ~8 s por cortes cortos del cable
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                dev = UsbPrinter.findFirst(ctx);
                if (dev != null) Logger.add(ctx, "USB apareció tras reintentar");
            }
            if (dev == null) return "No se detecta la impresora USB. Revisá el cable.";

            // Copiar el PDF a un archivo temporal (PdfRenderer necesita un descriptor "seekable")
            tmp = File.createTempFile("job", ".pdf", ctx.getCacheDir());
            if (src == null) return "El documento llegó vacío.";
            try (InputStream in = new FileInputStream(src.getFileDescriptor());
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try {
                    src.close();
                } catch (Exception ignored) {
                }
            }

            pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);

            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int dots = sp.getInt(KEY_DOTS, 384);

            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(new byte[]{0x1B, 0x40}); // ESC @

            Logger.add(ctx, "Páginas: " + renderer.getPageCount());

            for (int i = 0; i < renderer.getPageCount(); i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                Logger.add(ctx, "Pág " + (i + 1) + ": " + page.getWidth() + "x" + page.getHeight() + " pt");
                int w = dots;
                int h = Math.max(1, Math.round(page.getHeight() * (float) w / page.getWidth()));
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                page.close();

                writeRaster(o, bmp);
                bmp.recycle();
            }

            o.write(new byte[]{0x1B, 0x64, 0x05}); // ESC d 5 : avanzar papel
            return UsbPrinter.send(ctx, dev, o.toByteArray());
        } catch (Exception e) {
            return "Error al imprimir: " + e.getMessage();
        } finally {
            try {
                if (renderer != null) renderer.close();
            } catch (Exception ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
            if (tmp != null) tmp.delete();
        }
    }

    /** Convierte el bitmap a 1 bit y lo escribe como GS v 0 en franjas (quitando el blanco del final). */
    private void writeRaster(ByteArrayOutputStream o, Bitmap bmp) throws Exception {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);

        int bpr = (w + 7) / 8;
        byte[] bits = new byte[bpr * h];
        int lastInk = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                if (lum < 160) {
                    bits[y * bpr + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                    lastInk = y;
                }
            }
        }
        if (lastInk < 0) return; // página en blanco
        int rows = Math.min(h, lastInk + 12);

        final int band = 64;
        for (int y0 = 0; y0 < rows; y0 += band) {
            int n = Math.min(band, rows - y0);
            o.write(new byte[]{0x1D, 0x76, 0x30, 0x00,
                    (byte) (bpr & 0xFF), (byte) ((bpr >> 8) & 0xFF),
                    (byte) (n & 0xFF), (byte) ((n >> 8) & 0xFF)});
            o.write(bits, y0 * bpr, n * bpr);
        }
    }

    // ------------------------------------------------------------ Descubrimiento

    private class Session extends PrinterDiscoverySession {

        private PrinterInfo buildInfo() {
            PrinterId id = generatePrinterId("comandaprint-usb");

            PrinterCapabilitiesInfo caps = new PrinterCapabilitiesInfo.Builder(id)
                    .addMediaSize(new PrintAttributes.MediaSize("ROLL58_1000", "Rollo 58 mm (continuo)", 2283, 39370), true)
                    .addMediaSize(new PrintAttributes.MediaSize("ROLL58_297", "Rollo 58 mm (largo)", 2283, 11692), false)
                    .addMediaSize(new PrintAttributes.MediaSize("ROLL58_200", "Rollo 58 mm (corto)", 2283, 7874), false)
                    .addMediaSize(new PrintAttributes.MediaSize("ROLL80_1000", "Rollo 80 mm (continuo) - recibos Odoo", 3150, 39370), false)
                    .addMediaSize(new PrintAttributes.MediaSize("ROLL80_297", "Rollo 80 mm (largo)", 3150, 11692), false)
                    .addResolution(new PrintAttributes.Resolution("r203", "203 dpi", 203, 203), true)
                    .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                    .setMinMargins(new PrintAttributes.Margins(0, 0, 0, 0))
                    .build();

            return new PrinterInfo.Builder(id, "Térmica USB (ComandaPrint)", PrinterInfo.STATUS_IDLE)
                    .setCapabilities(caps)
                    .build();
        }

        /** Anuncia (o vuelve a anunciar) la impresora con todas sus capacidades. */
        void announce(String motivo) {
            try {
                List<PrinterInfo> list = new ArrayList<>();
                list.add(buildInfo());
                addPrinters(list);
                Logger.add(getApplicationContext(), "Impresora anunciada (" + motivo + ")");
            } catch (Throwable t) {
                Logger.add(getApplicationContext(), "Error anunciando impresora: " + t);
            }
        }

        @Override
        public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
            Logger.add(getApplicationContext(), "Android: inicia descubrimiento");
            announce("descubrimiento");
        }

        @Override
        public void onStopPrinterDiscovery() {
            Logger.add(getApplicationContext(), "Android: detiene descubrimiento");
        }

        @Override
        public void onValidatePrinters(List<PrinterId> printerIds) {
            Logger.add(getApplicationContext(), "Android: valida impresoras (" + printerIds.size() + ")");
            announce("validación");
        }

        @Override
        public void onStartPrinterStateTracking(PrinterId printerId) {
            Logger.add(getApplicationContext(), "Android: pide estado de la impresora");
            announce("estado");
        }

        @Override
        public void onStopPrinterStateTracking(PrinterId printerId) {
            Logger.add(getApplicationContext(), "Android: deja de pedir estado");
        }

        @Override
        public void onDestroy() {
            Logger.add(getApplicationContext(), "Sesión de descubrimiento destruida");
            if (session == this) session = null;
        }
    }
}
