package com.dicema.comandaprint;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

/** Utilidades para hablar con una impresora USB (clase 7) por bulk OUT. */
final class UsbPrinter {

    private UsbPrinter() {
    }

    static UsbInterface findPrinterInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER && findBulkOut(intf) != null) {
                return intf;
            }
        }
        return null;
    }

    static UsbEndpoint findBulkOut(UsbInterface intf) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                return ep;
            }
        }
        return null;
    }

    /** Primera impresora USB conectada, o null. */
    static UsbDevice findFirst(Context c) {
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        if (um == null) return null;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (findPrinterInterface(d) != null) return d;
        }
        return null;
    }

    /** Envía datos de forma síncrona. Devuelve null si salió bien, o un mensaje de error. */
    static String send(Context c, UsbDevice d, byte[] data) {
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection conn = null;
        UsbInterface intf = null;
        try {
            if (!um.hasPermission(d)) {
                return "Sin permiso USB. Abrí ComandaPrint con la impresora conectada y aceptá el permiso.";
            }
            intf = findPrinterInterface(d);
            if (intf == null) return "El dispositivo no tiene interfaz de impresora.";
            UsbEndpoint out = findBulkOut(intf);
            conn = um.openDevice(d);
            if (conn == null) return "No se pudo abrir el dispositivo USB.";
            if (!conn.claimInterface(intf, true)) return "No se pudo reclamar la interfaz USB.";

            int chunk = Math.max(64, Math.min(out.getMaxPacketSize() * 8, 4096));
            int offset = 0;
            while (offset < data.length) {
                int len = Math.min(chunk, data.length - offset);
                byte[] buf = new byte[len];
                System.arraycopy(data, offset, buf, 0, len);
                int r = conn.bulkTransfer(out, buf, len, 5000);
                if (r < 0) return "Error enviando datos por USB (código " + r + ").";
                offset += len;
            }
            return null;
        } catch (Exception e) {
            return "Error USB: " + e.getMessage();
        } finally {
            try {
                if (conn != null) {
                    if (intf != null) conn.releaseInterface(intf);
                    conn.close();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
