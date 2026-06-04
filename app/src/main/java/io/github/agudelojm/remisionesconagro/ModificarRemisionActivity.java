package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ModificarRemisionActivity extends AppCompatActivity {

    private TableLayout tlyTabla;
    private HashMap<String, String> userData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_modificar_remision);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Methods.updateActivity(this);
        
        Serializable serializable = getIntent().getSerializableExtra("userData");
        if (serializable instanceof HashMap) {
            userData = (HashMap<String, String>) serializable;
        }

        tlyTabla = findViewById(R.id.tly_TablaDatos_Modificar_Remision);
        cargarRemisiones();
    }

    private void cargarRemisiones() {
        List<Map<String, String>> remisiones = Methods.getListaRemisiones(this);
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
        
        for (Map<String, String> rem : remisiones) {
            TableRow row = new TableRow(this);
            row.setPadding(0, 10, 0, 10);

            row.addView(crearTextViewCelda(rem.get("consecutivo"), 0.8f));
            row.addView(crearTextViewCelda(rem.get("finca_nombre"), 1.8f));
            
            String fechaStr = rem.get("fecha");
            String formatted = "";
            if (fechaStr != null) {
                try {
                    formatted = sdf.format(new Date(Long.parseLong(fechaStr)));
                } catch (Exception e) { formatted = fechaStr; }
            }
            row.addView(crearTextViewCelda(formatted, 1.2f));
            
            Button btnMod = new Button(this);
            btnMod.setText("Modificar");
            btnMod.setOnClickListener(v -> {
                Intent intent = new Intent(this, EditarRemisionActivity.class);
                intent.putExtra("userData", userData);
                intent.putExtra("consecutivo", rem.get("consecutivo"));
                startActivity(intent);
            });
            row.addView(btnMod);

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
}
