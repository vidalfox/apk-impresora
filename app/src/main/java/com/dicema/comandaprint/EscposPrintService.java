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

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new Session();
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob job) {
        job.cancel();
    }

    @Override
    protected void onPrintJobQueued(final PrintJob job) {
        job.start();
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            final String error = doPrint(ctx, job);
            main.post(() -> {
                if (error == null) job.complete();
                else job.fail(error);
            });
        }).start();
    }

    // ------------------------------------------------------------ Impresión

    private String doPrint(Context ctx, PrintJob job) {
        File tmp = null;
        PdfRenderer renderer = null;
        ParcelFileDescriptor pfd = null;
        try {
            UsbDevice dev = UsbPrinter.findFirst(ctx);
            if (dev == null) return "No se detecta la impresora USB. Revisá el cable.";

            // Copiar el PDF a un archivo temporal (PdfRenderer necesita un descriptor "seekable")
            tmp = File.createTempFile("job", ".pdf", ctx.getCacheDir());
            ParcelFileDescriptor src = job.getDocument().getData();
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

            for (int i = 0; i < renderer.getPageCount(); i++) {
                PdfRenderer.Page page = renderer.openPage(i);
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

        final int band = 128;
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

        @Override
        public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
            PrinterId id = generatePrinterId("comandaprint-usb");

            PrinterCapabilitiesInfo caps = new PrinterCapabilitiesInfo.Builder(id)
                    .addMediaSize(new PrintAttributes.MediaSize("ROLL58_200", "Rollo 58 mm", 2283, 7874), true)
                    .addMediaSize(new PrintAttributes.MediaSize("ROLL58_297", "Rollo 58 mm (largo)", 2283, 11692), false)
                    .addResolution(new PrintAttributes.Resolution("r203", "203 dpi", 203, 203), true)
                    .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                    .setMinMargins(new PrintAttributes.Margins(0, 0, 0, 0))
                    .build();

            PrinterInfo info = new PrinterInfo.Builder(id, "Térmica USB (ComandaPrint)", PrinterInfo.STATUS_IDLE)
                    .setCapabilities(caps)
                    .build();

            List<PrinterInfo> list = new ArrayList<>();
            list.add(info);
            addPrinters(list);
        }

        @Override
        public void onStopPrinterDiscovery() {
        }

        @Override
        public void onValidatePrinters(List<PrinterId> printerIds) {
        }

        @Override
        public void onStartPrinterStateTracking(PrinterId printerId) {
        }

        @Override
        public void onStopPrinterStateTracking(PrinterId printerId) {
        }

        @Override
        public void onDestroy() {
        }
    }
}
