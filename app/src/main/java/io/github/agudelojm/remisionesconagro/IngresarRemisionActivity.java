package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
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

    private EditText edtFincaID, edtProductoRef, edtCantidad;
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
    }

    private void setupListeners() {
        // Search Finca Name when user finishes inputting ID
        edtFincaID.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String id = edtFincaID.getText().toString().trim();
                if (!id.isEmpty()) {
                    fincaActual = Methods.buscarCliente(id);
                    if (fincaActual != null) {
                        tvwNombreFinca.setText(fincaActual.getOrDefault("finca_nombre", "Finca encontrada"));
                        tvwNombreFinca.setTextColor(getResources().getColor(R.color.blue));
                    } else {
                        tvwNombreFinca.setText(id + " no encontrado");
                        tvwNombreFinca.setTextColor(Color.RED);
                    }
                }
            }
        });

        // Search Product Name when user finishes inputting Ref
        edtProductoRef.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && rbtProducto.isChecked()) {
                String ref = edtProductoRef.getText().toString().trim();
                if (!ref.isEmpty()) {
                    Map<String, String> prod = Methods.buscarProducto(ref);
                    if (prod != null) {
                        tvwNombreProducto.setText(prod.get("nombre"));
                        tvwNombreProducto.setTextColor(getResources().getColor(R.color.blue));
                    } else {
                        tvwNombreProducto.setText(ref + " no encontrado");
                        tvwNombreProducto.setTextColor(Color.RED);
                    }
                }
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
            itemsAgregados.add(new ItemRemision(
                    itemData.get("id"),
                    itemData.get("nombre"),
                    cantidadStr,
                    itemData.get("precio")
            ));
            actualizarTabla();
            
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

            TextView tvId = new TextView(this);
            tvId.setText(item.id);
            tvId.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 0.8f));

            TextView tvNombre = new TextView(this);
            tvNombre.setText(item.nombre);
            tvNombre.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 2.0f));

            TextView tvCant = new TextView(this);
            tvCant.setText(item.cantidad);
            tvCant.setLayoutParams(new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 0.6f));

            Button btnDel = new Button(this);
            btnDel.setText("X");
            btnDel.setTextColor(Color.WHITE);
            btnDel.setBackgroundColor(Color.RED);
            btnDel.setLayoutParams(new TableRow.LayoutParams(0, 100, 0.6f));
            
            final int index = i;
            btnDel.setOnClickListener(v -> {
                itemsAgregados.remove(index);
                actualizarTabla();
            });

            row.addView(tvId);
            row.addView(tvNombre);
            row.addView(tvCant);
            row.addView(btnDel);

            tlyTablaDatos.addView(row);
        }
    }

    private void finalizarRemision() {
        if (fincaActual == null || itemsAgregados.isEmpty()) {
            Methods.showAlert(this, "Complete los datos y agregue items antes de finalizar").show();
            return;
        }
        pedirFirmaTecnico();
    }

    private void pedirFirmaTecnico() {
        Intent intent = new Intent(this, PedirFirmaActivity.class);
        intent.putExtra("flowType", "remision_tecnico");
        intent.putExtra("userData", userData);
        signatureLauncher.launch(intent);
    }

    private void pedirFirmaCliente() {
        Intent intent = new Intent(this, PedirFirmaActivity.class);
        intent.putExtra("flowType", "remision_cliente");
        intent.putExtra("userData", userData);
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
            pdfItems.add(m);
        }

        File fileT = new File(getFilesDir(), "firma_tecnico.png");
        File fileC = new File(getFilesDir(), "firma_cliente.png");
        
        Bitmap bTecnico = BitmapFactory.decodeFile(fileT.getAbsolutePath());
        Bitmap bCliente = BitmapFactory.decodeFile(fileC.getAbsolutePath());

        File pdfFile = Methods.generarPDFRemision(this, consecutivo, fincaActual, pdfItems, userData, bTecnico, bCliente);
        
        // Clean up PNGs
        if (fileT.exists()) fileT.delete();
        if (fileC.exists()) fileC.delete();
        
        if (pdfFile != null && pdfFile.exists()) {
            enviarRemisionPorEmail(pdfFile);
        } else {
            Methods.showAlert(this, "Error generando el PDF de remisión.").show();
        }
    }

    private void enviarRemisionPorEmail(File pdfFile) {
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("application/pdf");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{""});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "Remisión " + consecutivo + " - " + 
                fincaActual.getOrDefault("finca_nombre", ""));
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Adjunto remisión generada.");

        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
        emailIntent.putExtra(Intent.EXTRA_STREAM, uri);
        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(emailIntent, "Enviar Remisión..."));
        finish();
    }

    private static class ItemRemision {
        String id, nombre, cantidad, precio;
        ItemRemision(String id, String nombre, String cantidad, String precio) {
            this.id = id;
            this.nombre = nombre;
            this.cantidad = cantidad;
            this.precio = precio;
        }
    }
}
