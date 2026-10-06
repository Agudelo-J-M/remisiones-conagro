package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EditarRemisionActivity extends AppCompatActivity {

    private static final String TAG = "EditarRemisionActivity";
    private TextView tvwFincaID, tvwNombreFinca, tvwNombreProducto;
    private AutoCompleteTextView edtProductoRef;
    private EditText edtCantidad;
    private RadioGroup rgpTipoItem;
    private RadioButton rbtProducto;
    private Spinner spnMantenimiento;
    private Button btnAnadirItem, btnFinalizar;
    private TableLayout tlyTabla;

    private final List<IngresarRemisionActivity.ItemRemision> itemsAgregados = new ArrayList<>();
    private final List<Map<String, String>> itemsOriginales = new ArrayList<>();
    private Map<String, String> fincaActual;
    private HashMap<String, String> userData;
    private String consecutivo = "";
    private String currentObservaciones = ObservacionesActivity.SIN_OBSERVACIONES;
    /** What the remisión was saved with, so the edit screen can offer it back for editing. */
    private String observacionesOriginales = "";

    private final ActivityResultLauncher<Intent> observacionesLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    currentObservaciones = result.getData()
                            .getStringExtra(ObservacionesActivity.RESULT_OBSERVACIONES);
                    pedirFirmaTecnico();
                }
            }
    );

    private final ActivityResultLauncher<Intent> signatureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null) {
                        String flow = data.getStringExtra("flowType");
                        if ("remision_tecnico".equals(flow)) {
                            pedirFirmaCliente();
                        } else if ("remision_cliente".equals(flow)) {
                            ejecutarGeneracionPDF();
                        }
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_editar_remision);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Methods.updateActivity(this);
        consecutivo = getIntent().getStringExtra("consecutivo");
        Serializable serializable = getIntent().getSerializableExtra("userData");
        if (serializable instanceof HashMap) {
            userData = (HashMap<String, String>) serializable;
        }

        initViews();
        setupListeners();
        loadRemisionData();
    }

    private void initViews() {
        TextView tvwTitulo = findViewById(R.id.tvw_Titulo_Editar_Remision);
        tvwFincaID = findViewById(R.id.tvw_FincaID_Editar_Remision);
        tvwNombreFinca = findViewById(R.id.tvw_NombreFinca_Editar_Remision);
        tvwNombreProducto = findViewById(R.id.tvw_NombreProducto_Editar_Remision);
        edtProductoRef = findViewById(R.id.edt_ProductoRef_Editar_Remision);
        edtCantidad = findViewById(R.id.edt_Cantidad_Editar_Remision);
        rgpTipoItem = findViewById(R.id.rgp_TipoItem_Editar_Remision);
        rbtProducto = findViewById(R.id.rbt_Producto_Editar_Remision);
        spnMantenimiento = findViewById(R.id.spn_Mantenimiento_Editar_Remision);
        btnAnadirItem = findViewById(R.id.btn_Anadir_Item_Editar_Remision);
        btnFinalizar = findViewById(R.id.btn_Finalizar_Editar_Remision);
        tlyTabla = findViewById(R.id.tly_TablaDatos_Editar_Remision);

        String titulo = "Editar Remisión - " + consecutivo;
        tvwTitulo.setText(titulo);

        CodigoNombreAdapter productAdapter = new CodigoNombreAdapter(this, Methods.getListaProductos());
        edtProductoRef.setAdapter(productAdapter);
        
        loadMantenimientos();
    }

    private void loadMantenimientos() {
        List<String> list = Methods.getListaMantenimientos();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnMantenimiento.setAdapter(adapter);
    }

    private void setupListeners() {
        edtProductoRef.setOnItemClickListener((parent, view, position, id) -> {
            String selected = (String) parent.getItemAtPosition(position);
            buscarYMostrarProducto(selected);
        });

        edtProductoRef.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && rbtProducto.isChecked()) {
                buscarYMostrarProducto(edtProductoRef.getText().toString().trim());
            }
        });

        rgpTipoItem.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbt_Producto_Editar_Remision) {
                edtProductoRef.setVisibility(View.VISIBLE);
                spnMantenimiento.setVisibility(View.GONE);
            } else {
                edtProductoRef.setVisibility(View.GONE);
                spnMantenimiento.setVisibility(View.VISIBLE);
            }
        });

        btnAnadirItem.setOnClickListener(v -> agregarItem());
        btnFinalizar.setOnClickListener(v -> finalizarEdicion());
    }

    private void loadRemisionData() {
        JSONObject json = Methods.loadRemisionData(this, consecutivo);
        if (json == null) {
            Methods.showErrorAndFinishActivity(this, "No se pudo cargar la remisión.");
            return;
        }
        try {
            // Absent on remisiones saved before observaciones were persisted: "" then means the
            // edit screen opens with an empty field, exactly as it always did.
            observacionesOriginales = json.optString(ObservacionesActivity.RESULT_OBSERVACIONES, "");
            if (ObservacionesActivity.SIN_OBSERVACIONES.equalsIgnoreCase(observacionesOriginales)) {
                // The placeholder is not something the técnico typed, so do not offer it back.
                observacionesOriginales = "";
            }

            JSONObject fincaJson = json.getJSONObject("fincaData");
            fincaActual = new HashMap<>();
            JSONArray names = fincaJson.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.getString(i);
                    fincaActual.put(key, fincaJson.getString(key));
                }
            }
            tvwFincaID.setText(fincaActual.getOrDefault("finca_id", ""));
            tvwNombreFinca.setText(fincaActual.getOrDefault("finca_nombre", fincaActual.getOrDefault("direccion", "")));

            JSONArray itemsArray = json.getJSONArray("items");
            for (int i = 0; i < itemsArray.length(); i++) {
                JSONObject itemJson = itemsArray.getJSONObject(i);
                IngresarRemisionActivity.ItemRemision item = new IngresarRemisionActivity.ItemRemision(
                        itemJson.getString("id"),
                        itemJson.getString("nombre"),
                        itemJson.getString("cantidad"),
                        itemJson.getString("precio"),
                        itemJson.optString("tipo", "producto")
                );
                itemsAgregados.add(item);
                
                // Cache ORIGINAL items for Virtual Stock Calculation
                Map<String, String> original = new HashMap<>();
                original.put("id", item.id);
                original.put("cantidad", item.cantidad);
                original.put("tipo", item.tipo);
                itemsOriginales.add(original);
            }
            
            // REMOVED: Immediate stock restoration. 
            // We only restore stock physically once the user confirms successful edit.
            
            actualizarTabla();
        } catch (Exception e) {
            Log.e(TAG, "Error loading remission data", e);
        }
    }

    private List<Map<String, String>> itemsToMap(List<IngresarRemisionActivity.ItemRemision> items) {
        List<Map<String, String>> list = new ArrayList<>();
        for (IngresarRemisionActivity.ItemRemision item : items) {
            Map<String, String> m = new HashMap<>();
            m.put("id", item.id);
            m.put("nombre", item.nombre);
            m.put("cantidad", item.cantidad);
            m.put("precio", item.precio);
            m.put("tipo", item.tipo);
            list.add(m);
        }
        return list;
    }

    private void buscarYMostrarProducto(String query) {
        if (query.isEmpty()) return;
        Map<String, String> prod = Methods.buscarProducto(query);
        if (prod != null) {
            tvwNombreProducto.setText(prod.get("nombre"));
            tvwNombreProducto.setTextColor(Color.BLUE);
            edtProductoRef.setText(prod.get("id"), false);
        } else {
            tvwNombreProducto.setText(query + " no encontrado");
            tvwNombreProducto.setTextColor(Color.RED);
        }
    }

    private void agregarItem() {
        String id, nombre, precio;
        String cantidadStr = edtCantidad.getText().toString().trim();
        if (cantidadStr.isEmpty() || "0".equals(cantidadStr)) {
            Methods.showAlert(this, "Ingrese una cantidad válida").show();
            return;
        }
        int qtyToAdd = Integer.parseInt(cantidadStr);

        if (rbtProducto.isChecked()) {
            String ref = edtProductoRef.getText().toString().trim();
            Map<String, String> prod = Methods.buscarProducto(ref);
            if (prod == null) {
                Methods.showAlert(this, "Producto no encontrado").show();
                return;
            }
            id = prod.get("id");
            nombre = prod.get("nombre");
            precio = prod.get("precio");
            
            /* Reenable later if strictly needed
            // Rule: VIRTUAL STOCK Logic
            // available = (Physical Car Stock) + (Original items in THIS remission)
            HashMap<String, Integer> carInv = Methods.getCarInventory(this);
            int currentInCar = carInv.getOrDefault(id, 0);
            
            int quantityInOriginalRemission = 0;
            for (Map<String, String> orig : itemsOriginales) {
                if (orig.get("id").equalsIgnoreCase(id)) {
                    quantityInOriginalRemission += Integer.parseInt(orig.get("cantidad"));
                }
            }
            
            int totalVirtualAvailable = currentInCar + quantityInOriginalRemission;

            if (totalVirtualAvailable == 0) {
                Methods.showAlert(this, "Error: El producto no se encuentra en el inventario del carro.").show();
                return;
            }

            int alreadyInEditList = 0;
            for (IngresarRemisionActivity.ItemRemision existing : itemsAgregados) {
                if (existing.id.equalsIgnoreCase(id)) {
                    alreadyInEditList += Integer.parseInt(existing.cantidad);
                }
            }

            if (alreadyInEditList + qtyToAdd > totalVirtualAvailable) {
                Methods.showAlert(this, "Error: La cantidad (" + (alreadyInEditList + qtyToAdd) + ") supera el inventario disponible (Stock: " + currentInCar + " + Remisión Original: " + quantityInOriginalRemission + ")").show();
                return;
            }
            */
        } else {
            String selection = (String) spnMantenimiento.getSelectedItem();
            Map<String, String> mant = Methods.buscarMantenimiento(selection);
            if (mant == null) return;
            id = mant.get("id");
            nombre = mant.get("nombre");
            precio = mant.get("precio");
        }

        // Sum quantities if item already exists
        boolean exists = false;
        String currentTipo = rbtProducto.isChecked() ? "producto" : "mantenimiento";
        for (IngresarRemisionActivity.ItemRemision existing : itemsAgregados) {
            boolean isSame = false;
            if ("producto".equals(currentTipo)) {
                isSame = "producto".equals(existing.tipo) && existing.id.equalsIgnoreCase(id);
            } else {
                isSame = "mantenimiento".equals(existing.tipo) && existing.nombre.equalsIgnoreCase(nombre);
            }

            if (isSame) {
                int currentQty = Integer.parseInt(existing.cantidad);
                existing.cantidad = String.valueOf(currentQty + qtyToAdd);
                exists = true;
                break;
            }
        }

        if (!exists) {
            itemsAgregados.add(new IngresarRemisionActivity.ItemRemision(id, nombre, cantidadStr, precio, currentTipo));
        }

        actualizarTabla();
        edtProductoRef.setText("");
        tvwNombreProducto.setText("");
        edtCantidad.setText("1");
    }

    private void actualizarTabla() {
        int count = tlyTabla.getChildCount();
        if (count > 1) tlyTabla.removeViews(1, count - 1);

        for (int i = 0; i < itemsAgregados.size(); i++) {
            IngresarRemisionActivity.ItemRemision item = itemsAgregados.get(i);
            TableRow row = new TableRow(this);
            row.setPadding(8, 8, 8, 8);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            
            row.addView(crearTextViewCelda(item.id, 1.1f));
            
            TextView tvNombre = crearTextViewCelda(item.nombre, 1.8f);
            tvNombre.setSingleLine(false);
            tvNombre.setMaxLines(3);
            row.addView(tvNombre);

            row.addView(crearTextViewCelda(item.cantidad, 0.5f));
            
            Button btnDelete = new Button(this, null, androidx.appcompat.R.attr.buttonStyleSmall);
            btnDelete.setText("X");
            btnDelete.setTextColor(Color.WHITE);
            btnDelete.setBackgroundColor(Color.RED);
            btnDelete.setMinWidth(0);
            btnDelete.setMinimumWidth(0);
            btnDelete.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 0.6f));

            int finalI = i;
            btnDelete.setOnClickListener(v -> {
                itemsAgregados.remove(finalI);
                actualizarTabla();
            });
            row.addView(btnDelete);
            
            tlyTabla.addView(row);
        }
    }

    private TextView crearTextViewCelda(String texto, float weight) {
        TextView tv = new TextView(this);
        tv.setText(texto);
        tv.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight));
        tv.setPadding(12, 12, 12, 12);
        return tv;
    }

    private void finalizarEdicion() {
        if (itemsAgregados.isEmpty()) {
            Methods.showAlert(this, "Agregue items antes de finalizar").show();
            return;
        }

        // Feature: Validate Email and Show Office Reminder
        String email = fincaActual != null ? fincaActual.getOrDefault("persona_email", "") : "";
        if (email == null || email.trim().isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Email Faltante")
                    .setMessage("El email del cliente no está en la base de datos. Por favor solicítelo para enviarle una copia.")
                    .setPositiveButton("Continuar", (dialog, which) -> showOfficeReminder())
                    .show();
        } else {
            showOfficeReminder();
        }
    }

    /**
     * The office address is filled into the email automatically from the "CORREO OFICINA"
     * column, so this reminder is only worth showing when we do not have it: a técnico whose
     * cell is blank on the server, or a phone still on a database saved before that column
     * existed. In both cases the técnico still has to type it, exactly as before.
     */
    private void showOfficeReminder() {
        if (!Methods.getCorreoOficina(userData).isEmpty()) {
            continuarTrasRecordatorio();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Recordatorio")
                .setMessage("No olvide ingresar el correo de la oficina para enviar esta remisión.")
                .setPositiveButton("Entendido", (dialog, which) -> continuarTrasRecordatorio())
                .show();
    }

    private void continuarTrasRecordatorio() {
        Intent intent = new Intent(this, ObservacionesActivity.class);
        // Offer the original text back instead of making the técnico retype it from memory: an
        // edit used to replace whatever the remisión said with the empty field's placeholder.
        if (!observacionesOriginales.isEmpty()) {
            intent.putExtra(ObservacionesActivity.EXTRA_PRELLENADO, observacionesOriginales);
        }
        observacionesLauncher.launch(intent);
    }

    private void pedirFirmaTecnico() {
        Intent intent = new Intent(this, PedirFirmaActivity.class);
        intent.putExtra("flowType", "remision_tecnico");
        intent.putExtra("userData", userData);
        intent.putExtra("consecutivo", consecutivo);
        signatureLauncher.launch(intent);
    }

    private void pedirFirmaCliente() {
        Intent intent = new Intent(this, PedirFirmaActivity.class);
        intent.putExtra("flowType", "remision_cliente");
        intent.putExtra("userData", userData);
        intent.putExtra("consecutivo", consecutivo);
        signatureLauncher.launch(intent);
    }

    private void ejecutarGeneracionPDF() {
        // 1. Deduct New Stock (Actual physical update happens ONLY ON CONFIRMATION)
        List<Map<String, String>> pdfItems = itemsToMap(itemsAgregados);
        
        // 2. Generate PDF
        File fileT = new File(getFilesDir(), "firma_tecnico_" + consecutivo + ".png");
        File fileC = new File(getFilesDir(), "firma_cliente_" + consecutivo + ".png");
        Bitmap bT = BitmapFactory.decodeFile(fileT.getAbsolutePath());
        Bitmap bC = BitmapFactory.decodeFile(fileC.getAbsolutePath());

        File pdfFile = Methods.generarPDFRemision(this, consecutivo, fincaActual, pdfItems, userData, bT, bC, currentObservaciones);
        
        if (pdfFile != null && pdfFile.exists()) {
            enviarRemisionPorEmail(pdfFile, fileT, fileC, pdfItems);
        } else {
            // Cleanup on failure
            if (fileT.exists()) fileT.delete();
            if (fileC.exists()) fileC.delete();
            Methods.showAlert(this, "Error generando PDF").show();
        }
    }

    private void enviarRemisionPorEmail(File pdf, File t, File c, List<Map<String, String>> pdfItems) {
        String clientEmail = fincaActual != null ? fincaActual.getOrDefault("persona_email", "") : "";
        // Office address comes from the Tecnicos sheet ("CORREO OFICINA"), so the técnico no
        // longer types it. Blanks are dropped, never sent as an empty recipient.
        String oficinaEmail = Methods.getCorreoOficina(userData);

        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("application/pdf");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, Methods.buildEmailRecipients(clientEmail, oficinaEmail));
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Remisión Editada " + consecutivo);
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Adjunto remisión actualizada.");
        emailIntent.putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdf));
        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(emailIntent, "Enviar..."));
        
        // Confirmation Prompt
        new android.app.AlertDialog.Builder(this)
                .setTitle("Confirmación")
                .setMessage("¿Se envió el correo correctamente? Si confirma, se aplicarán los cambios al inventario permanentemente.")
                .setPositiveButton("Sí, enviado", (dialog, which) -> {
                    // 1. RESTORE OLD STOCK FIRST (Physically)
                    Methods.restaurarStock(this, itemsOriginales);
                    
                    // 2. DEDUCT NEW STOCK (Physically)
                    Methods.updateWarehouseStock(this, pdfItems);
                    // Methods.updateCarStock(this, pdfItems); // Temporarily disabled

                    // 3. SECURE DELETION of signatures and PDF
                    if (t.exists()) t.delete();
                    if (c.exists()) c.delete();
                    if (pdf.exists()) pdf.delete();
                    
                    // 4. Update permanent metadata
                    Methods.saveRemisionData(this, consecutivo, fincaActual, pdfItems, userData, false, currentObservaciones);

                    finish();
                })
                .setNegativeButton("No", (dialog, which) -> {
                    // Signatures must be deleted regardless
                    if (t.exists()) t.delete();
                    if (c.exists()) c.delete();
                    if (pdf.exists()) pdf.delete();
                    Methods.showAlert(this, "El envío fue cancelado. Las firmas han sido borradas por seguridad, firme de nuevo para reintentar.").show();
                })
                .setCancelable(false)
                .show();
    }
}
