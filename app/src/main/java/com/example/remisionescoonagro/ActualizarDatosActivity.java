package com.example.remisionescoonagro;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;

public class ActualizarDatosActivity extends AppCompatActivity {

    private FileInputStream fileInputStream;

    Context context = this;
    Uri uri;

    Workbook wb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_actualizar_datos);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        Intent intent = getIntent();
        uri = intent.getData();
        setFileInputStream();
    }

    private void setFileInputStream() {
        try {
            fileInputStream = new FileInputStream(context.getContentResolver().openFileDescriptor(uri, "r").getFileDescriptor());
            loadWorkbook();
            fileInputStream.close();
            //makeWbSaveable();
        } catch (Exception e) {
            Log.w("Error leyendo", e.toString());

        }
        Log.w("contenidoWB", wb.getSheetAt(1).getRow(0).getCell(0).toString() );

    }
    private void loadWorkbook() {
        try {
            if (fileInputStream != null) {
                OPCPackage pkg = OPCPackage.open(fileInputStream);
                wb = new XSSFWorkbook(pkg);
                Log.w("WB creado", wb.toString());
                fileInputStream.close();

            }
        } catch (Exception e) {
            Log.w("error leyendo wb", e.toString());
        }
    }
}