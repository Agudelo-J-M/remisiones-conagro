package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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

    // Feature 6: placeholder client used for anonymous / nonexistent-client remissions
    private static final String PLACEHOLDER_FINCA_ID = "000";
    private static final String PLACEHOLDER_FINCA_NOMBRE = "CLIENTE POR DEFINIR";
    private boolean isAnonymousRemision = false;

    // Consecutivo is assigned at commit time, so signatures are captured under a fixed draft key
    // (no number exists yet). See docs/feature-consecutivo-remision.md.
    private static final String SIGNATURE_DRAFT_KEY = "DRAFT";

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

        // Fresh-install gate: block remisiones until the consecutivo counter is configured.
        if (!Methods.isConsecutivoInicializado(this, tecnicoNumero)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Consecutivo no configurado")
                    .setMessage("Debe configurar el número de consecutivo antes de crear remisiones.")
                    .setCancelable(false)
                    .setPositiveButton("Configurar", (d, w) -> {
                        Intent intent = new Intent(this, CambiarConsecutivoActivity.class);
                        intent.putExtra("userData", userData);
                        startActivity(intent);
                        finish();
                    })
                    .show();
            return;
        }

        initViews();
        setupListeners();
        loadMantenimientos();

        // Consecutivo is assigned at commit time (see ejecutarGeneracionPDF), not on screen open,
        // so an abandoned remisión never burns a number. Until then this is only a draft.
        tvwTitulo.setText(getString(R.string.ingresar_remisi_n) + " (Borrador)");

        // Feature 1: Load draft if exists
        loadDraft();
    }

    private void loadDraft() {
        RemisionDraftCache.Draft draft = RemisionDraftCache.get(tecnicoNumero);
        if (draft == null) return;

        // Capture references up front: setting the finca field below triggers the TextWatcher,
        // whose saveDraft() would otherwise overwrite draft.items before we finish restoring them.
        Map<String, String> savedFinca = draft.finca;
        List<Map<String, String>> savedItems = draft.items;

        // Restore items first, so the finca field's watcher persists them (not an empty list).
        if (savedItems != null && !savedItems.isEmpty()) {
            for (Map<String, String> m : savedItems) {
                itemsAgregados.add(new ItemRemision(
                        m.getOrDefault("id", ""),
                        m.getOrDefault("nombre", ""),
                        m.getOrDefault("cantidad", "1"),
                        m.getOrDefault("precio", "0"),
                        m.getOrDefault("tipo", "producto")
                ));
            }
            actualizarTabla();
        }

        // Never restore the placeholder into a new session; the watcher resolves the name.
        if (savedFinca != null) {
            String id = savedFinca.getOrDefault("finca_id", "");
            if (!isPlaceholderId(id)) {
                edtFincaID.setText(id, false);
                edtFincaID.setSelection(id.length());
            }
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
        RemisionDraftCache.saveRemision(tecnicoNumero, fincaActual, mappedItems);
    }

    private void initViews() {
        tvwTitulo = findViewById(R.id.tvw_Titulo_Ingresar_Remision);
        edtFincaID = findViewById(R.id.edt_FincaID_Ingresar_Remision);
        tvwNombreFinca = findViewById(R.id.tvw_NombreFinca_Ingresar_Remision);
        tvwNombreFinca.setText("Cliente sin registrar");
        tvwNombreFinca.setTextColor(Color.GRAY);
        
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
        // Feature 6: Invalidate the cached client the moment the field no longer matches it,
        // so a deleted/edited ID can never carry stale finca data into the PDF or email.
        // Guarded so that programmatic setText(id) after a successful lookup does NOT wipe it.
        edtFincaID.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                // Live client resolution: refresh the name label as the ID changes
                mostrarNombreFinca(s.toString().trim());
                saveDraft();
            }
        });

        // Autocomplete selection: clean the field down to just the ID; the watcher resolves the name
        edtFincaID.setOnItemClickListener((parent, view, position, id) -> {
            String selected = (String) parent.getItemAtPosition(position);
            String fincaId = selected.split(" - ")[0].trim();
            edtFincaID.setText(fincaId, false);
            edtFincaID.setSelection(fincaId.length());
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

    /**
     * Resolves the finca for the given ID and updates the name label live, WITHOUT rewriting the
     * field text. Shows the matching client name, or "Cliente sin registrar" when empty/unknown.
     */
    private void mostrarNombreFinca(String query) {
        Map<String, String> resolved = query.isEmpty() ? null : Methods.buscarCliente(query);
        if (resolved != null) {
            fincaActual = resolved;
            tvwNombreFinca.setText(resolved.getOrDefault("finca_nombre", "Finca encontrada"));
            tvwNombreFinca.setTextColor(getResources().getColor(R.color.blue));
        } else {
            fincaActual = null;
            tvwNombreFinca.setText("Cliente sin registrar");
            tvwNombreFinca.setTextColor(Color.GRAY);
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

        // Feature 6: Resolve the client authoritatively from the field at generation time,
        // so the branch decision and the PDF/email data always agree (single source of truth).
        String typedId = edtFincaID.getText().toString().trim();

        if (typedId.isEmpty()) {
            // Empty -> anonymous / nonexistent-client workflow (placeholders + client data into observaciones)
            fincaActual = null;
            isAnonymousRemision = true;
        } else {
            Map<String, String> resolved = Methods.buscarCliente(typedId);
            if (resolved == null) {
                // Typed an ID not in the database -> block; do NOT proceed with empty or placeholders
                Methods.showAlert(this, "Cliente no encontrado. Revise el ID de la finca.").show();
                return;
            }
            fincaActual = resolved;
            isAnonymousRemision = false;
        }

        if (isAnonymousRemision) {
            // Skip email validation and show office reminder immediately
            showOfficeReminder();
            return;
        }

        // Standard Client Flow - Validate Email and Show Office Reminder
        String email = fincaActual.getOrDefault("persona_email", "");
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

    private void showOfficeReminder() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Recordatorio")
                .setMessage("No olvide ingresar el correo de la oficina para enviar esta remisión.")
                .setPositiveButton("Entendido", (dialog, which) -> {
                    if (isAnonymousRemision) {
                        // NEW / NONEXISTENT CLIENT FLOW: capture client data into observaciones
                        Intent intent = new Intent(this, IngresarDatosClienteActivity.class);
                        intent.putExtra("tecnicoNumero", tecnicoNumero);
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
        // No consecutivo yet (assigned at commit); capture under the fixed draft key.
        intent.putExtra("consecutivo", SIGNATURE_DRAFT_KEY);
        signatureLauncher.launch(intent);
    }

    private void pedirFirmaCliente() {
        Intent intent = new Intent(this, PedirFirmaActivity.class);
        intent.putExtra("flowType", "remision_cliente");
        intent.putExtra("userData", userData);
        // No consecutivo yet (assigned at commit); capture under the fixed draft key.
        intent.putExtra("consecutivo", SIGNATURE_DRAFT_KEY);
        signatureLauncher.launch(intent);
    }

    private void ejecutarGeneracionPDF() {
        // Peek the next consecutivo at commit time (not consumed until the send is confirmed).
        // A missing técnico número is fatal (peekProximoConsecutivo closes the app).
        consecutivo = Methods.peekProximoConsecutivo(this, tecnicoNumero);
        if (consecutivo == null || consecutivo.isEmpty()) {
            // Fatal error already surfaced; clean up draft signatures on the way out.
            File dT = new File(getFilesDir(), "firma_tecnico_" + SIGNATURE_DRAFT_KEY + ".png");
            File dC = new File(getFilesDir(), "firma_cliente_" + SIGNATURE_DRAFT_KEY + ".png");
            if (dT.exists()) dT.delete();
            if (dC.exists()) dC.delete();
            return;
        }

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

        // Feature 6: anonymous remissions render with placeholder finca data
        Map<String, String> pdfFinca = isAnonymousRemision ? buildPlaceholderFinca() : fincaActual;

        // Signatures were captured under the draft key (no consecutivo existed yet).
        File fileT = new File(getFilesDir(), "firma_tecnico_" + SIGNATURE_DRAFT_KEY + ".png");
        File fileC = new File(getFilesDir(), "firma_cliente_" + SIGNATURE_DRAFT_KEY + ".png");

        Bitmap bTecnico = BitmapFactory.decodeFile(fileT.getAbsolutePath());
        Bitmap bCliente = BitmapFactory.decodeFile(fileC.getAbsolutePath());

        File pdfFile = Methods.generarPDFRemision(this, consecutivo, pdfFinca, pdfItems, userData, bTecnico, bCliente, currentObservaciones);
        
        if (pdfFile != null && pdfFile.exists()) {
            // Deduct from inventories
            Methods.updateWarehouseStock(this, pdfItems);
            // Methods.updateCarStock(this, pdfItems); // Temporarily disabled

            enviarRemisionPorEmail(pdfFile, fileT, fileC, pdfItems, pdfFinca);
        } else {
            // If PDF failed, still clean up sensitive signatures
            if (fileT.exists()) fileT.delete();
            if (fileC.exists()) fileC.delete();
            Methods.showAlert(this, "Error generando el PDF de remisión.").show();
        }
    }

    private void enviarRemisionPorEmail(File pdfFile, File sigT, File sigC, List<Map<String, String>> pdfItems, Map<String, String> pdfFinca) {
        String clientEmail = pdfFinca != null ? pdfFinca.getOrDefault("persona_email", "") : "";
        
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("application/pdf");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{clientEmail});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Remisión " + consecutivo + " - " + 
                pdfFinca.getOrDefault("finca_nombre", ""));
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
                    Methods.saveRemisionData(this, consecutivo, pdfFinca, pdfItems, userData, false);

                    // Consume the consecutivo only now that the send is confirmed
                    Methods.commitConsecutivo(this, tecnicoNumero, consecutivo);

                    // FLUSH DRAFT and local state (draft kept on failure so it can be resent)
                    RemisionDraftCache.clear(tecnicoNumero);
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

    private static boolean isPlaceholderId(String id) {
        return PLACEHOLDER_FINCA_ID.equals(id);
    }

    private Map<String, String> buildPlaceholderFinca() {
        Map<String, String> finca = new HashMap<>();
        finca.put("finca_nombre", PLACEHOLDER_FINCA_NOMBRE);
        finca.put("finca_id", PLACEHOLDER_FINCA_ID);
        finca.put("direccion", "N/A");
        finca.put("ciu", "N/A");
        finca.put("persona_nombre", "POR DEFINIR");
        finca.put("persona_telefono", "N/A");
        finca.put("persona_email", "");
        return finca;
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
