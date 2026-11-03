package com.dicema.comandaprint;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.dicema.comandaprint.USB_PERMISSION";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Codepages: nombre, valor de ESC t n, nombres de charset en Java
    private static final String[] CP_NAMES = {"CP858 (n=19)", "CP850 (n=2)", "CP437 (n=0)", "WPC1252 (n=16)"};
    private static final int[] CP_N = {19, 2, 0, 16};
    private static final String[][] CP_CHARSETS = {
            {"Cp858", "IBM00858", "IBM858"},
            {"Cp850", "IBM850"},
            {"Cp437", "IBM437"},
            {"windows-1252", "Cp1252"}
    };

    private RadioButton rbUsb;
    private RadioButton rbBt;
    private Spinner spDevice;
    private Spinner spCodepage;
    private TextView tvStatus;

    private final List<BluetoothDevice> btDevices = new ArrayList<>();
    private final List<UsbDevice> usbDevices = new ArrayList<>();

    private byte[] pendingUsbData = null;
    private UsbDevice pendingUsbDevice = null;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && pendingUsbDevice != null && pendingUsbData != null) {
                final UsbDevice d = pendingUsbDevice;
                final byte[] data = pendingUsbData;
                pendingUsbDevice = null;
                pendingUsbData = null;
                sendUsbAsync(d, data);
            } else {
                status("Permiso USB denegado.");
                pendingUsbDevice = null;
                pendingUsbData = null;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }

        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
        }

        refreshDevices();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshDevices();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("ComandaPrint");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView lbl0 = label("Conexión");
        root.addView(lbl0);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        rbUsb = new RadioButton(this);
        rbUsb.setText("USB (cable)");
        rbUsb.setId(View.generateViewId());
        rbBt = new RadioButton(this);
        rbBt.setText("Bluetooth");
        rbBt.setId(View.generateViewId());
        rg.addView(rbUsb);
        rg.addView(rbBt);
        rbUsb.setChecked(true);
        rg.setOnCheckedChangeListener((group, checkedId) -> refreshDevices());
        root.addView(rg);

        root.addView(label("Impresora"));
        spDevice = new Spinner(this);
        root.addView(spDevice);

        Button btnRefresh = new Button(this);
        btnRefresh.setText("Actualizar lista");
        btnRefresh.setOnClickListener(v -> refreshDevices());
        root.addView(btnRefresh);

        root.addView(label("Codepage (acentos)"));
        spCodepage = new Spinner(this);
        spCodepage.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CP_NAMES));
        root.addView(spCodepage);

        Button btnPrint = new Button(this);
        btnPrint.setText("Imprimir comanda de prueba");
        btnPrint.setOnClickListener(v -> printTest());
        root.addView(btnPrint);

        // ---- Servicio de impresión (para imprimir desde Chrome u otras apps)
        root.addView(label("Imprimir desde Chrome / otras apps"));

        TextView info = new TextView(this);
        info.setText("1) Activá el servicio 'ComandaPrint' en los ajustes de impresión.\n"
                + "2) Conectá la impresora por USB y, cuando Android lo pida, aceptá el permiso "
                + "(marcá 'usar siempre').\n"
                + "3) En Chrome: menú > Compartir/Imprimir > elegí 'Térmica USB (ComandaPrint)'.");
        root.addView(info);

        Button btnPrintSettings = new Button(this);
        btnPrintSettings.setText("Abrir ajustes de impresión");
        btnPrintSettings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_PRINT_SETTINGS));
            } catch (Exception e) {
                status("No se pudo abrir los ajustes de impresión. Buscá 'Impresión' en Ajustes.");
            }
        });
        root.addView(btnPrintSettings);

        root.addView(label("Ancho de papel (impresión desde Chrome)"));
        Spinner spWidth = new Spinner(this);
        spWidth.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"58 mm (384 puntos)", "80 mm (576 puntos)"}));
        final android.content.SharedPreferences sp =
                getSharedPreferences(EscposPrintService.PREFS, Context.MODE_PRIVATE);
        spWidth.setSelection(sp.getInt(EscposPrintService.KEY_DOTS, 384) == 576 ? 1 : 0);
        spWidth.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                sp.edit().putInt(EscposPrintService.KEY_DOTS, position == 1 ? 576 : 384).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(spWidth);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, dp(16), 0, 0);
        tvStatus.setText("Listo.");
        root.addView(tvStatus);

        setContentView(sv);
    }

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private void status(final String msg) {
        runOnUiThread(() -> tvStatus.setText(msg));
    }

    // ---------------------------------------------------------------- Dispositivos

    private void refreshDevices() {
        if (spDevice == null) return;
        List<String> names = new ArrayList<>();
        btDevices.clear();
        usbDevices.clear();

        if (rbUsb.isChecked()) {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (um != null) {
                for (UsbDevice d : um.getDeviceList().values()) {
                    if (findPrinterInterface(d) != null) {
                        usbDevices.add(d);
                        String n = d.getProductName() != null ? d.getProductName() : "Impresora USB";
                        names.add(n + String.format(" [%04X:%04X]", d.getVendorId(), d.getProductId()));
                    }
                }
            }
            if (names.isEmpty()) {
                names.add("(no se detecta ninguna impresora USB)");
                status("Conectá la impresora con el cable USB y tocá 'Actualizar lista'. "
                        + "La tablet debe soportar USB host (OTG).");
            } else {
                status("Impresoras USB encontradas: " + usbDevices.size());
            }
        } else {
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null) {
                    names.add("(este equipo no tiene Bluetooth)");
                } else if (!ad.isEnabled()) {
                    names.add("(Bluetooth apagado)");
                } else {
                    for (BluetoothDevice d : ad.getBondedDevices()) {
                        btDevices.add(d);
                        names.add(d.getName() + " [" + d.getAddress() + "]");
                    }
                    if (names.isEmpty()) names.add("(no hay impresoras emparejadas)");
                }
            } catch (SecurityException e) {
                names.add("(falta permiso de Bluetooth)");
            }
        }
        spDevice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }

    // ---------------------------------------------------------------- Ticket

    private Charset charsetFor(int idx) {
        for (String n : CP_CHARSETS[idx]) {
            try {
                return Charset.forName(n);
            } catch (Exception ignored) {
            }
        }
        return Charset.forName("windows-1252");
    }

    private byte[] buildTicket(int cpIdx) throws Exception {
        Charset cs = charsetFor(cpIdx);
        ByteArrayOutputStream o = new ByteArrayOutputStream();

        o.write(new byte[]{0x1B, 0x40});                       // ESC @  (reset)
        o.write(new byte[]{0x1B, 0x74, (byte) CP_N[cpIdx]});   // ESC t n (codepage)

        o.write(new byte[]{0x1B, 0x61, 0x01});                 // centrado
        o.write(new byte[]{0x1B, 0x45, 0x01});                 // negrita
        o.write("COMANDA DE PRUEBA\n".getBytes(cs));
        o.write(new byte[]{0x1B, 0x45, 0x00});
        o.write("Codepage: ".concat(CP_NAMES[cpIdx]).concat("\n").getBytes(cs));
        o.write(new byte[]{0x1B, 0x61, 0x00});                 // izquierda

        o.write("--------------------------------\n".getBytes(cs));
        o.write("Mesa 5          Mesero: María\n".getBytes(cs));
        o.write("--------------------------------\n".getBytes(cs));
        o.write("2 x Café con leche\n".getBytes(cs));
        o.write("1 x Piña colada\n".getBytes(cs));
        o.write("1 x Ñandú a la plancha\n".getBytes(cs));
        o.write("   Nota: sin cebolla, ¡bien cocido!\n".getBytes(cs));
        o.write("--------------------------------\n".getBytes(cs));
        o.write("áéíóú ÁÉÍÓÚ ñ Ñ ü ¿? ¡!\n".getBytes(cs));
        o.write("\n\n\n\n".getBytes(cs));
        return o.toByteArray();
    }

    private void printTest() {
        final byte[] data;
        try {
            data = buildTicket(spCodepage.getSelectedItemPosition());
        } catch (Exception e) {
            status("Error armando el ticket: " + e.getMessage());
            return;
        }

        int pos = spDevice.getSelectedItemPosition();
        if (rbUsb.isChecked()) {
            if (pos < 0 || pos >= usbDevices.size()) {
                status("No hay impresora USB seleccionada.");
                return;
            }
            printUsb(usbDevices.get(pos), data);
        } else {
            if (pos < 0 || pos >= btDevices.size()) {
                status("No hay impresora Bluetooth seleccionada.");
                return;
            }
            printBt(btDevices.get(pos), data);
        }
    }

    // ---------------------------------------------------------------- USB

    private UsbInterface findPrinterInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER && findBulkOut(intf) != null) {
                return intf;
            }
        }
        return null;
    }

    private UsbEndpoint findBulkOut(UsbInterface intf) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                return ep;
            }
        }
        return null;
    }

    private void printUsb(UsbDevice d, byte[] data) {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (um.hasPermission(d)) {
            sendUsbAsync(d, data);
            return;
        }
        pendingUsbDevice = d;
        pendingUsbData = data;
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent i = new Intent(ACTION_USB_PERMISSION);
        i.setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, flags);
        status("Pidiendo permiso USB... aceptalo en el diálogo.");
        um.requestPermission(d, pi);
    }

    private void sendUsbAsync(final UsbDevice d, final byte[] data) {
        status("Enviando por USB...");
        new Thread(() -> {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            UsbDeviceConnection conn = null;
            UsbInterface intf = null;
            try {
                intf = findPrinterInterface(d);
                if (intf == null) {
                    status("Error: el dispositivo no tiene interfaz de impresora.");
                    return;
                }
                UsbEndpoint out = findBulkOut(intf);
                conn = um.openDevice(d);
                if (conn == null) {
                    status("Error: no se pudo abrir el dispositivo USB.");
                    return;
                }
                if (!conn.claimInterface(intf, true)) {
                    status("Error: no se pudo reclamar la interfaz USB.");
                    return;
                }
                int offset = 0;
                int chunk = Math.max(64, Math.min(out.getMaxPacketSize() * 8, 4096));
                while (offset < data.length) {
                    int len = Math.min(chunk, data.length - offset);
                    byte[] buf = new byte[len];
                    System.arraycopy(data, offset, buf, 0, len);
                    int r = conn.bulkTransfer(out, buf, len, 5000);
                    if (r < 0) {
                        status("Error enviando datos por USB (código " + r + ").");
                        return;
                    }
                    offset += len;
                }
                status("Impreso por USB (" + data.length + " bytes). Revisá el papel.");
            } catch (Exception e) {
                status("Error USB: " + e.getMessage());
            } finally {
                try {
                    if (conn != null) {
                        if (intf != null) conn.releaseInterface(intf);
                        conn.close();
                    }
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- Bluetooth

    private void printBt(final BluetoothDevice d, final byte[] data) {
        status("Conectando por Bluetooth...");
        new Thread(() -> {
            BluetoothSocket sock = null;
            try {
                BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
                try {
                    sock = d.createRfcommSocketToServiceRecord(SPP_UUID);
                    sock.connect();
                } catch (Exception first) {
                    // Respaldo: socket RFCOMM por reflexión (canal 1)
                    try {
                        if (sock != null) sock.close();
                    } catch (Exception ignored) {
                    }
                    sock = (BluetoothSocket) d.getClass()
                            .getMethod("createRfcommSocket", int.class).invoke(d, 1);
                    sock.connect();
                }
                OutputStream os = sock.getOutputStream();
                os.write(data);
                os.flush();
                Thread.sleep(1500);
                status("Impreso por Bluetooth (" + data.length + " bytes). Revisá el papel.");
            } catch (SecurityException e) {
                status("Falta el permiso de Bluetooth.");
            } catch (Exception e) {
                status("Error Bluetooth: " + e.getMessage());
            } finally {
                try {
                    if (sock != null) sock.close();
                } catch (Exception ignored) {
                }
            }
        }).start();
    }
}
