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
import android.widget.ImageButton;
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

        ArrayAdapter<String> productAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, Methods.getListaProductos());
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
                        itemJson.getString("precio")
                );
                itemsAgregados.add(item);
                
                Map<String, String> original = new HashMap<>();
                original.put("id", item.id);
                original.put("cantidad", item.cantidad);
                itemsOriginales.add(original);
            }
            
            // Logic: Restore stock when modification starts
            if (!json.optBoolean("restored", false)) {
                Methods.restaurarStock(this, itemsOriginales);
                // Mark as restored so we don't do it again if user re-enters modification
                Methods.saveRemisionData(this, consecutivo, fincaActual, itemsToMap(itemsAgregados), userData, true);
            }
            
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
        String id, nombre, cantidad, precio;
        cantidad = edtCantidad.getText().toString().trim();
        if (cantidad.isEmpty() || "0".equals(cantidad)) {
            Methods.showAlert(this, "Ingrese una cantidad válida").show();
            return;
        }

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
            
            // Check InventarioCarro
            HashMap<String, Integer> carInv = Methods.getCarInventory(this);
            if (!carInv.containsKey(id)) {
                Methods.showAlert(this, "Error: El producto no se encuentra en el inventario del carro.").show();
                return;
            }
            Integer currentQty = carInv.get(id);
            if ((currentQty != null ? currentQty : 0) < Integer.parseInt(cantidad)) {
                Methods.showAlert(this, "Advertencia: Cantidad en carro insuficiente (" + currentQty + ")").show();
                // We still allow it as per requirement, but showing warning.
            }
        } else {
            String selection = (String) spnMantenimiento.getSelectedItem();
            Map<String, String> mant = Methods.buscarMantenimiento(selection);
            if (mant == null) return;
            id = mant.get("referencia");
            nombre = mant.get("descripcion");
            precio = mant.get("precio_base");
        }

        itemsAgregados.add(new IngresarRemisionActivity.ItemRemision(id, nombre, cantidad, precio));
        actualizarTabla();
        edtProductoRef.setText("");
        tvwNombreProducto.setText("");
    }

    private void actualizarTabla() {
        int count = tlyTabla.getChildCount();
        if (count > 1) tlyTabla.removeViews(1, count - 1);

        for (int i = 0; i < itemsAgregados.size(); i++) {
            IngresarRemisionActivity.ItemRemision item = itemsAgregados.get(i);
            TableRow row = new TableRow(this);
            row.setPadding(0, 10, 0, 10);
            
            row.addView(crearTextViewCelda(item.id, 0.8f));
            row.addView(crearTextViewCelda(item.nombre, 2f));
            row.addView(crearTextViewCelda(item.cantidad, 0.6f));
            
            ImageButton btnDelete = new ImageButton(this);
            btnDelete.setImageResource(android.R.drawable.ic_menu_delete);
            btnDelete.setBackgroundColor(Color.TRANSPARENT);
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
        pedirFirmaTecnico();
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
        // 1. Deduct New Stock (Old stock was already restored at start)
        List<Map<String, String>> pdfItems = itemsToMap(itemsAgregados);
        
        Methods.updateWarehouseStock(this, pdfItems);
        Methods.updateCarStock(this, pdfItems);

        // 2. Save New JSON (marking restored=false since it's now finalized with new stock)
        Methods.saveRemisionData(this, consecutivo, fincaActual, pdfItems, userData, false);

        // 3. Generate PDF
        File fileT = new File(getFilesDir(), "firma_tecnico_" + consecutivo + ".png");
        File fileC = new File(getFilesDir(), "firma_cliente_" + consecutivo + ".png");
        Bitmap bT = BitmapFactory.decodeFile(fileT.getAbsolutePath());
        Bitmap bC = BitmapFactory.decodeFile(fileC.getAbsolutePath());

        File pdfFile = Methods.generarPDFRemision(this, consecutivo, fincaActual, pdfItems, userData, bT, bC);
        
        if (pdfFile != null && pdfFile.exists()) {
            enviarRemisionPorEmail(pdfFile, fileT, fileC);
        } else {
            Methods.showAlert(this, "Error generando PDF").show();
        }
    }

    private void enviarRemisionPorEmail(File pdf, File t, File c) {
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("application/pdf");
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Remisión Editada " + consecutivo);
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Adjunto remisión actualizada.");
        emailIntent.putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdf));
        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(emailIntent, "Enviar..."));
        
        if (t.exists()) t.delete();
        if (c.exists()) c.delete();
        finish();
    }
}
