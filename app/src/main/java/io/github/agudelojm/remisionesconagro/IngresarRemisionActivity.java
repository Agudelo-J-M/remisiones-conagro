package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IngresarRemisionActivity extends AppCompatActivity {

    private AutoCompleteTextView edtFincaID, edtProductoRef;
    private EditText edtCantidad;
    private TextView tvwNombreFinca, tvwNombreProducto, tvwTitulo;
    private RadioGroup rgpTipoItem;
    private RadioButton rbtProducto, rbtMantenimiento;
    private LinearLayout llyInputContainer;
    private Spinner spnMantenimiento;
    private Button btnAnadirItem, btnFinalizarRemision;
    private TableLayout tlyTablaDatos;

    private List<ItemRemision> itemsAgregados = new ArrayList<>();
    private Map<String, String> fincaActual = null;
    private HashMap<String, String> userData;
    private String tecnicoNumero = "";
    private String consecutivo = "";
    private String currentObservaciones = "Sin observaciones";

    private final ActivityResultLauncher<Intent> clientDataLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    currentObservaciones = result.getData().getStringExtra("observaciones");
                    pedirFirmaTecnico();
                }
            }
    );

    private final ActivityResultLauncher<Intent> observacionesLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    currentObservaciones = result.getData().getStringExtra("observaciones");
                    pedirFirmaTecnico();
                }
            }
    );

    private final ActivityResultLauncher<Intent> signatureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String flow = result.getData().getStringExtra("flowType");
                    if ("remision_tecnico".equals(flow)) {
                        pedirFirmaCliente();
                    } else if ("remision_cliente".equals(flow)) {
                        ejecutarGeneracionPDF();
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ingresar_remision);
        Methods.updateActivity(this);

        // Retrieve técnico data
        userData = (HashMap<String, String>) getIntent().getSerializableExtra("userData");
        if (userData != null) {
            tecnicoNumero = userData.getOrDefault("numero", "");
        }

        // Generate and validate consecutivo
        consecutivo = Methods.generarConsecutivo(this, tecnicoNumero);

        initViews();
        setupListeners();
        loadMantenimientos();

        if (consecutivo != null && !consecutivo.isEmpty()) {
            tvwTitulo.setText(getString(R.string.ingresar_remisi_n) + " - " + consecutivo);
        }

        // Feature 1: Load draft if exists
        loadDraft();
    }

    private void loadDraft() {
        JSONObject draft = Methods.loadDraftRemision(this);
        if (draft == null) return;
        try {
            if (draft.has("fincaData")) {
                JSONObject fincaJson = draft.getJSONObject("fincaData");
                fincaActual = new HashMap<>();
                java.util.Iterator<String> keys = fincaJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    fincaActual.put(key, fincaJson.getString(key));
                }
                edtFincaID.setText(fincaActual.getOrDefault("finca_id", ""), false);
                tvwNombreFinca.setText(fincaActual.getOrDefault("finca_nombre", ""));
                tvwNombreFinca.setTextColor(getResources().getColor(R.color.blue));
            }
            if (draft.has("items")) {
                org.json.JSONArray itemsArray = draft.getJSONArray("items");
                for (int i = 0; i < itemsArray.length(); i++) {
                    JSONObject itemJson = itemsArray.getJSONObject(i);
                    itemsAgregados.add(new ItemRemision(
                            itemJson.getString("id"),
                            itemJson.getString("nombre"),
                            itemJson.getString("cantidad"),
                            itemJson.getString("precio"),
                            itemJson.optString("tipo", "producto")
                    ));
                }
                actualizarTabla();
            }
        } catch (Exception e) {
            Log.e("Draft", "Error loading draft", e);
        }
    }

    private void saveDraft() {
        List<Map<String, String>> mappedItems = new ArrayList<>();
        for (ItemRemision item : itemsAgregados) {
            Map<String, String> m = new HashMap<>();
            m.put("id", item.id);
            m.put("nombre", item.nombre);
            m.put("cantidad", item.cantidad);
            m.put("precio", item.precio);
            m.put("tipo", item.tipo);
            mappedItems.add(m);
        }
        Methods.saveDraftRemision(this, fincaActual, mappedItems, null);
    }

    private void initViews() {
        tvwTitulo = findViewById(R.id.tvw_Titulo_Ingresar_Remision);
        edtFincaID = findViewById(R.id.edt_FincaID_Ingresar_Remision);
        tvwNombreFinca = findViewById(R.id.tvw_NombreFinca_Ingresar_Remision);
        
        rgpTipoItem = findViewById(R.id.rgp_TipoItem_Ingresar_Remision);
        rbtProducto = findViewById(R.id.rbt_Producto_Ingresar_Remision);
        rbtMantenimiento = findViewById(R.id.rbt_Mantenimiento_Ingresar_Remision);
        
        llyInputContainer = findViewById(R.id.lly_InputContainer_Ingresar_Remision);
        tvwNombreProducto = findViewById(R.id.tvw_NombreProducto_Ingresar_Remision);
        edtProductoRef = findViewById(R.id.edt_ProductoRef_Ingresar_Remision);
        spnMantenimiento = findViewById(R.id.spn_Mantenimiento_Ingresar_Remision);
        edtCantidad = findViewById(R.id.edt_Cantidad_Ingresar_Remision);
        
        btnAnadirItem = findViewById(R.id.btn_Anadir_Item_Ingresar_Remision);
        btnFinalizarRemision = findViewById(R.id.btn_Finalizar_Remision_Ingresar_Remision);
        tlyTablaDatos = findViewById(R.id.tly_TablaDatos_Ingresar_Remision);

        // Set up Autocomplete
        ArrayAdapter<String> fincaAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, Methods.getListaFincas());
        edtFincaID.setAdapter(fincaAdapter);

        ArrayAdapter<String> productAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, Methods.getListaProductos());
        edtProductoRef.setAdapter(productAdapter);
    }

    private void setupListeners() {
        // Search Finca Name when user selects an item or finishes inputting ID
        edtFincaID.setOnItemClickListener((parent, view, position, id) -> {
            String selected = (String) parent.getItemAtPosition(position);
            buscarYMostrarFinca(selected);
        });

        edtFincaID.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                buscarYMostrarFinca(edtFincaID.getText().toString().trim());
            }
        });

        // Search Product Name when user selects an item or finishes inputting Ref
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
            if (checkedId == R.id.rbt_Producto_Ingresar_Remision) {
                edtProductoRef.setVisibility(View.VISIBLE);
                findViewById(R.id.lly_LabelProducto_Ingresar_Remision).setVisibility(View.VISIBLE);
                spnMantenimiento.setVisibility(View.GONE);
            } else {
                edtProductoRef.setVisibility(View.GONE);
                findViewById(R.id.lly_LabelProducto_Ingresar_Remision).setVisibility(View.GONE);
                spnMantenimiento.setVisibility(View.VISIBLE);
            }
        });

        btnAnadirItem.setOnClickListener(v -> agregarItem());
        btnFinalizarRemision.setOnClickListener(v -> finalizarRemision());
    }

    private void buscarYMostrarFinca(String query) {
        if (query.isEmpty()) return;
        fincaActual = Methods.buscarCliente(query);
        if (fincaActual != null) {
            tvwNombreFinca.setText(fincaActual.getOrDefault("finca_nombre", "Finca encontrada"));
            tvwNombreFinca.setTextColor(getResources().getColor(R.color.blue));
            // Update text to just the ID to avoid messy submission
            edtFincaID.setText(fincaActual.getOrDefault("finca_id", query), false);
            saveDraft();
        } else {
            tvwNombreFinca.setText(query + " no encontrado");
            tvwNombreFinca.setTextColor(Color.RED);
        }
    }

    private void buscarYMostrarProducto(String query) {
        if (query.isEmpty()) return;
        Map<String, String> prod = Methods.buscarProducto(query);
        if (prod != null) {
            tvwNombreProducto.setText(prod.get("nombre"));
            tvwNombreProducto.setTextColor(getResources().getColor(R.color.blue));
            // Update text to just the ID
            edtProductoRef.setText(prod.get("id"), false);
        } else {
            tvwNombreProducto.setText(query + " no encontrado");
            tvwNombreProducto.setTextColor(Color.RED);
        }
    }

    private void loadMantenimientos() {
        List<String> mantenimientos = Methods.getListaMantenimientos();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, mantenimientos);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnMantenimiento.setAdapter(adapter);
    }

    private void agregarItem() {
        String fincaId = edtFincaID.getText().toString().trim();
        if (fincaId.isEmpty()) {
            Methods.showAlert(this, "Debe ingresar el ID de la Finca").show();
            return;
        }

        if (fincaActual == null || !fincaId.equalsIgnoreCase(fincaActual.get("finca_id"))) {
            fincaActual = Methods.buscarCliente(fincaId);
            if (fincaActual == null) {
                Methods.showAlert(this, "Finca no encontrada").show();
                return;
            }
        }

        String cantidadStr = edtCantidad.getText().toString().trim();
        if (cantidadStr.isEmpty() || Integer.parseInt(cantidadStr) <= 0) {
            Methods.showAlert(this, "Ingrese una cantidad válida").show();
            return;
        }
        int qtyToAdd = Integer.parseInt(cantidadStr);

        Map<String, String> itemData = null;
        if (rbtProducto.isChecked()) {
            String ref = edtProductoRef.getText().toString().trim();
            itemData = Methods.buscarProducto(ref);
        } else {
            Object selected = spnMantenimiento.getSelectedItem();
            if (selected != null) {
                itemData = Methods.buscarMantenimiento(selected.toString());
            }
        }

        if (itemData != null) {
            String id = itemData.get("id");
            /* Reenable later if strictly needed ... */

            // Rule 2: Sum quantities if item already exists in the list
            boolean exists = false;
            String currentTipo = rbtProducto.isChecked() ? "producto" : "mantenimiento";
            for (ItemRemision existing : itemsAgregados) {
                boolean isSame;
                if ("producto".equals(currentTipo)) {
                    // Products: Unique by ID (Reference)
                    isSame = "producto".equals(existing.tipo) && existing.id.equalsIgnoreCase(id);
                } else {
                    // Maintenance: Unique by Name (Description) because Referencias repeat
                    isSame = "mantenimiento".equals(existing.tipo) && existing.nombre.equalsIgnoreCase(itemData.get("nombre"));
                }

                if (isSame) {
                    int currentQty = Integer.parseInt(existing.cantidad);
                    existing.cantidad = String.valueOf(currentQty + qtyToAdd);
                    exists = true;
                    break;
                }
            }

            if (!exists) {
                itemsAgregados.add(new ItemRemision(
                        id,
                        itemData.get("nombre"),
                        cantidadStr,
                        itemData.get("precio"),
                        currentTipo
                ));
            }

            actualizarTabla();
            saveDraft();
            
            // Clear inputs
            edtProductoRef.setText("");
            tvwNombreProducto.setText("");
            edtCantidad.setText("1");
        } else {
            Methods.showAlert(this, "Item no encontrado").show();
        }
    }

    private void actualizarTabla() {
        int childCount = tlyTablaDatos.getChildCount();
        if (childCount > 1) {
            tlyTablaDatos.removeViews(1, childCount - 1);
        }

        for (int i = 0; i < itemsAgregados.size(); i++) {
            ItemRemision item = itemsAgregados.get(i);
            TableRow row = new TableRow(this);
            row.setPadding(8, 8, 8, 8);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView tvId = new TextView(this);
            tvId.setText(item.id);
            tvId.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1.1f));

            TextView tvNombre = new TextView(this);
            tvNombre.setText(item.nombre);
            tvNombre.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1.8f));
            tvNombre.setSingleLine(false); // Enable wrap
            tvNombre.setMaxLines(3);

            TextView tvCant = new TextView(this);
            tvCant.setText(item.cantidad);
            tvCant.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 0.5f));

            Button btnDel = new Button(this, null, androidx.appcompat.R.attr.buttonStyleSmall);
            btnDel.setText("X");
            btnDel.setTextColor(Color.WHITE);
            btnDel.setBackgroundColor(Color.RED);
            btnDel.setMinWidth(0);
            btnDel.setMinimumWidth(0);
            btnDel.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 0.6f));
            
            final int index = i;
            btnDel.setOnClickListener(v -> {
                itemsAgregados.remove(index);
                actualizarTabla();
                saveDraft();
            });

            row.addView(tvId);
            row.addView(tvNombre);
            row.addView(tvCant);
            row.addView(btnDel);

            tlyTablaDatos.addView(row);
        }
    }

    private void finalizarRemision() {
        if (itemsAgregados.isEmpty()) {
            Methods.showAlert(this, "Agregue items antes de finalizar").show();
            return;
        }

        // Feature 6: Support for Placeholder Client (Anonymous Remission)
        if (fincaActual == null) {
            fincaActual = new HashMap<>();
            fincaActual.put("finca_nombre", "CLIENTE POR DEFINIR");
            fincaActual.put("finca_id", "000");
            fincaActual.put("direccion", "N/A");
            fincaActual.put("ciu", "N/A");
            fincaActual.put("persona_nombre", "POR DEFINIR");
            fincaActual.put("persona_telefono", "N/A");
            fincaActual.put("persona_email", "");
            Log.d("Remision", "Using placeholder client data");
        }

        // Feature: Validate Email and Show Office Reminder
        String email = fincaActual.getOrDefault("persona_email", "").trim();
        if (email.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Email Faltante")
                    .setMessage("El email del cliente no está en la base de datos. Por favor solicítelo para enviarle una copia.")
                    .setPositiveButton("Continuar", (dialog, which) -> showOfficeReminder())
                    .show();
        } else {
            showOfficeReminder();
        }
    }

    private void showOfficeReminder() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Recordatorio")
                .setMessage("No olvide ingresar el correo de la oficina para enviar esta remisión.")
                .setPositiveButton("Entendido", (dialog, which) -> {
                    if (fincaActual == null) {
                        // NEW CLIENT FLOW
                        Intent intent = new Intent(this, IngresarDatosClienteActivity.class);
                        clientDataLauncher.launch(intent);
                    } else {
                        // EXISTING CLIENT FLOW
                        Intent intent = new Intent(this, ObservacionesActivity.class);
                        observacionesLauncher.launch(intent);
                    }
                })
                .show();
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
        List<Map<String, String>> pdfItems = new ArrayList<>();
        for (ItemRemision item : itemsAgregados) {
            Map<String, String> m = new HashMap<>();
            m.put("id", item.id);
            m.put("nombre", item.nombre);
            m.put("cantidad", item.cantidad);
            m.put("precio", item.precio);
            m.put("tipo", item.tipo); // CRITICAL: Propagate type to filtering logic
            pdfItems.add(m);
        }

        File fileT = new File(getFilesDir(), "firma_tecnico_" + consecutivo + ".png");
        File fileC = new File(getFilesDir(), "firma_cliente_" + consecutivo + ".png");
        
        Bitmap bTecnico = BitmapFactory.decodeFile(fileT.getAbsolutePath());
        Bitmap bCliente = BitmapFactory.decodeFile(fileC.getAbsolutePath());

        File pdfFile = Methods.generarPDFRemision(this, consecutivo, fincaActual, pdfItems, userData, bTecnico, bCliente, currentObservaciones);
        
        if (pdfFile != null && pdfFile.exists()) {
            // Deduct from inventories
            Methods.updateWarehouseStock(this, pdfItems);
            // Methods.updateCarStock(this, pdfItems); // Temporarily disabled

            enviarRemisionPorEmail(pdfFile, fileT, fileC, pdfItems);
        } else {
            // If PDF failed, still clean up sensitive signatures
            if (fileT.exists()) fileT.delete();
            if (fileC.exists()) fileC.delete();
            Methods.showAlert(this, "Error generando el PDF de remisión.").show();
        }
    }

    private void enviarRemisionPorEmail(File pdfFile, File sigT, File sigC, List<Map<String, String>> pdfItems) {
        String clientEmail = fincaActual != null ? fincaActual.getOrDefault("persona_email", "") : "";
        
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("application/pdf");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{clientEmail});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Remisión " + consecutivo + " - " + 
                fincaActual.getOrDefault("finca_nombre", ""));
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Adjunto remisión generada.");

        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
        emailIntent.putExtra(Intent.EXTRA_STREAM, uri);
        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(emailIntent, "Enviar Remisión..."));
        
        // Confirmation Prompt
        new android.app.AlertDialog.Builder(this)
                .setTitle("Confirmación")
                .setMessage("¿Se envió el correo correctamente? Si confirma, se limpiarán los datos y se guardará la remisión.")
                .setPositiveButton("Sí, enviado", (dialog, which) -> {
                    // SECURE DELETION of signatures and PDF
                    if (sigT.exists()) sigT.delete();
                    if (sigC.exists()) sigC.delete();
                    if (pdfFile.exists()) pdfFile.delete();
                    
                    // SAVE Metadata
                    Methods.saveRemisionData(this, consecutivo, fincaActual, pdfItems, userData, false);
                    
                    // FLUSH DRAFT and local state
                    Methods.deleteDraftRemision(this);
                    itemsAgregados.clear();
                    fincaActual = null;
                    
                    finish();
                })
                .setNegativeButton("No", (dialog, which) -> {
                    // ALWAYS delete signatures and PDF regardless of status for privacy
                    if (sigT.exists()) sigT.delete();
                    if (sigC.exists()) sigC.delete();
                    if (pdfFile.exists()) pdfFile.delete();
                    
                    actualizarTabla(); 

                    // We DO NOT save the metadata and DO NOT delete the draft.
                    // Technician must sign again.
                    Methods.showAlert(this, "El envío fue cancelado. Las firmas han sido borradas por seguridad, firme de nuevo para reintentar.").show();
                })
                .setCancelable(false)
                .show();
    }

    public static class ItemRemision {
        public String id, nombre, cantidad, precio, tipo;
        public ItemRemision(String id, String nombre, String cantidad, String precio, String tipo) {
            this.id = id;
            this.nombre = nombre;
            this.cantidad = cantidad;
            this.precio = precio;
            this.tipo = tipo;
        }
    }
}
