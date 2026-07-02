package io.github.agudelojm.remisionesconagro;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Activity to capture additional comments (Observaciones) for the remission document.
 */
public class ObservacionesActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_observaciones);

        Methods.updateActivity(this);

        EditText edtObs = findViewById(R.id.edt_Observaciones);
        Button btnNext = findViewById(R.id.btn_Siguiente_Observaciones);

        btnNext.setOnClickListener(v -> {
            String obs = edtObs.getText().toString().trim();
            if (obs.isEmpty()) obs = "Sin observaciones";
            
            Intent result = new Intent();
            result.putExtra("observaciones", obs);
            setResult(RESULT_OK, result);
            finish();
        });
    }
}
