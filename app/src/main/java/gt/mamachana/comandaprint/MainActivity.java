package gt.mamachana.comandaprint;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Version minima: elige una impresora termica ya emparejada por Bluetooth
 * y le manda una comanda de prueba por ESC/POS. Sin consultar a Odoo todavia.
 */
public class MainActivity extends Activity {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Tablas de caracteres a probar (comando ESC t n) y su charset de Java.
    private static final String[] CP_NAMES = {
            "CP858 (n=19)", "CP850 (n=2)", "CP437 (n=0)", "WPC1252 (n=16)"
    };
    private static final int[] CP_N = {19, 2, 0, 16};
    private static final String[] CP_CHARSET = {
            "IBM00858", "IBM850", "IBM437", "windows-1252"
    };

    private Spinner spDevice;
    private Spinner spCodepage;
    private TextView status;
    private final List<BluetoothDevice> devices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Comanda Print - prueba");
        title.setTextSize(22);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("1) Empareja la impresora en Ajustes de Bluetooth del Android.\n"
                + "2) Elige la impresora aqui.\n"
                + "3) Prueba las tablas de caracteres hasta que las tildes salgan bien.");
        help.setPadding(0, dp(8), 0, dp(8));
        root.addView(help);

        TextView l1 = new TextView(this);
        l1.setText("Impresora:");
        root.addView(l1);
        spDevice = new Spinner(this);
        root.addView(spDevice);

        TextView l2 = new TextView(this);
        l2.setText("Tabla de caracteres:");
        l2.setPadding(0, dp(8), 0, 0);
        root.addView(l2);
        spCodepage = new Spinner(this);
        spCodepage.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, CP_NAMES));
        root.addView(spCodepage);

        Button btnRefresh = new Button(this);
        btnRefresh.setText("Actualizar lista de impresoras");
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshDevices(); }
        });
        root.addView(btnRefresh);

        Button btnPrint = new Button(this);
        btnPrint.setText("Imprimir comanda de prueba");
        btnPrint.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { printTest(); }
        });
        root.addView(btnPrint);

        status = new TextView(this);
        status.setPadding(0, dp(12), 0, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(status);
        root.addView(sv);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            refreshDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshDevices();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void log(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() { status.append(msg + "\n"); }
        });
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private void refreshDevices() {
        devices.clear();
        List<String> names = new ArrayList<>();
        try {
            BluetoothAdapter ad = adapter();
            if (ad == null) {
                log("Este equipo no tiene Bluetooth.");
            } else if (!ad.isEnabled()) {
                log("Bluetooth apagado. Enciendelo y toca Actualizar.");
            } else {
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                for (BluetoothDevice d : bonded) {
                    devices.add(d);
                    names.add(d.getName() + "  (" + d.getAddress() + ")");
                }
                log("Dispositivos emparejados: " + devices.size());
            }
        } catch (SecurityException e) {
            log("Falta el permiso de Bluetooth. Concedelo en Ajustes > Apps > Comanda Print.");
        }
        spDevice.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
    }

    private void printTest() {
        if (devices.isEmpty()) {
            log("No hay impresora seleccionada. Empareja una y toca Actualizar.");
            return;
        }
        final BluetoothDevice device = devices.get(spDevice.getSelectedItemPosition());
        final int cp = spCodepage.getSelectedItemPosition();
        log("Imprimiendo en " + device.getName() + " con " + CP_NAMES[cp] + " ...");

        new Thread(new Runnable() {
            @Override public void run() {
                BluetoothSocket socket = null;
                try {
                    BluetoothAdapter ad = adapter();
                    if (ad != null) ad.cancelDiscovery();
                    try {
                        socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                        socket.connect();
                    } catch (Exception first) {
                        // Muchas impresoras baratas solo conectan por este metodo alterno.
                        try { if (socket != null) socket.close(); } catch (Exception ignore) { }
                        socket = (BluetoothSocket) device.getClass()
                                .getMethod("createRfcommSocket", int.class)
                                .invoke(device, 1);
                        socket.connect();
                    }
                    OutputStream os = socket.getOutputStream();
                    os.write(buildTicket(cp));
                    os.flush();
                    Thread.sleep(1500); // dejar que termine de vaciar el buffer
                    log("OK: enviado.");
                } catch (Exception e) {
                    log("ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    try { if (socket != null) socket.close(); } catch (Exception ignore) { }
                }
            }
        }).start();
    }

    // ---------- ESC/POS ----------

    private byte[] buildTicket(int cp) throws Exception {
        Charset cs;
        try {
            cs = Charset.forName(CP_CHARSET[cp]);
        } catch (Exception e) {
            cs = Charset.forName("ISO-8859-1");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(new byte[]{0x1B, 0x40});                         // inicializar
        out.write(new byte[]{0x1B, 0x74, (byte) CP_N[cp]});        // tabla de caracteres

        out.write(new byte[]{0x1B, 0x61, 0x01});                   // centrado
        out.write(new byte[]{0x1B, 0x21, 0x30});                   // doble alto y ancho
        text(out, cs, "COMANDA");
        out.write(new byte[]{0x1B, 0x21, 0x00});                   // normal
        text(out, cs, "PRUEBA DE IMPRESION");
        out.write(new byte[]{0x1B, 0x61, 0x00});                   // izquierda

        text(out, cs, "--------------------------------");         // 32 columnas (58 mm)
        text(out, cs, "Cocina: Cafetería");
        text(out, cs, "Mesa: 4          Orden: 002");
        text(out, cs, "--------------------------------");
        out.write(new byte[]{0x1B, 0x45, 0x01});                   // negrita
        text(out, cs, "2 x Café con leche");
        text(out, cs, "1 x Pastel de zanahoria");
        out.write(new byte[]{0x1B, 0x45, 0x00});                   // fin negrita
        text(out, cs, "   (sin azúcar)");
        text(out, cs, "--------------------------------");
        text(out, cs, "Minúsculas: á é í ó ú ñ ü");
        text(out, cs, "Mayúsculas: Á É Í Ó Ú Ñ");
        text(out, cs, "Signos: ¿Qué tal? ¡Listo! Q 25.00");
        text(out, cs, "Tabla: " + CP_NAMES[cp]);
        text(out, cs, new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(new Date()));

        out.write(new byte[]{0x1B, 0x64, 0x05});                   // avanzar 5 lineas
        return out.toByteArray();
    }

    private void text(ByteArrayOutputStream out, Charset cs, String s) throws Exception {
        out.write(s.getBytes(cs));
        out.write(0x0A);
    }
}
