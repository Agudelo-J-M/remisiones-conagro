package io.github.agudelojm.remisionesconagro;

import android.content.Context;
import android.util.Log;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-disk home of the four "Datos App" tables.
 *
 * <p><b>Why not keep the .xlsx.</b> The workbook was never the app's data model, only its delivery
 * format: the server sends CSV per table and the correo sends a workbook, but every consumer reads
 * strings by column name. Keeping .xlsx as the stored form meant re-parsing ~100 MB of POI DOM at
 * every launch and at every sync, on the UI thread. The store therefore holds one CSV per table —
 * which the sync can write without POI touching it at all — and the workbook survives only as an
 * import format on the email path.
 *
 * <p><b>Legacy phones.</b> A phone updating from an older build has no store, only
 * {@link #LEGACY_WORKBOOK}. {@link Methods#initializeData} migrates it once (parse, convert, save,
 * close) and from then on the store is authoritative. The legacy file is left in place rather than
 * deleted: it is small, and keeping it means a downgrade does not strand the técnico with no data.
 */
final class DatosAppStore {

    private static final String TAG = "DatosAppStore";

    /** The pre-store format, still what the correo delivers and what older builds wrote. */
    static final String LEGACY_WORKBOOK = "Datos App.xlsx";

    private static final String DIR = "datos_app";

    /**
     * The four tables: {store file base name, sheet/table name}.
     *
     * <p>The second element is matched exactly — it is the sheet name on the email path AND the
     * {@code ?tabla=} value the endpoint expects (lowercased first element), so do not "tidy"
     * either one. Note "tarifas" is the "Tarifas Mantenimiento" sheet.
     */
    static final String[][] TABLES = {
            {"tecnicos", "Tecnicos"},
            {"productos", "Productos"},
            {"clientes", "Clientes"},
            {"tarifas", "Tarifas Mantenimiento"},
    };

    private DatosAppStore() { }

    private static File dir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    private static File file(Context context, String base) {
        return new File(dir(context), base + ".csv");
    }

    /** True when all four tables are present in the store. A partial store counts as absent. */
    static boolean exists(Context context) {
        for (String[] t : TABLES) {
            if (!file(context, t[0]).exists()) return false;
        }
        return true;
    }

    /** True when the técnico has data of SOME kind — the store, or a legacy workbook to migrate. */
    static boolean hasAnyLocalData(Context context) {
        return exists(context) || new File(context.getFilesDir(), LEGACY_WORKBOOK).exists();
    }

    /**
     * Reads the whole store. Returns null if anything is missing or unreadable, so the caller can
     * fall back rather than adopt a half-populated database.
     */
    static Map<String, Tabla> load(Context context) {
        Map<String, Tabla> tablas = new LinkedHashMap<>();
        for (String[] t : TABLES) {
            File f = file(context, t[0]);
            if (!f.exists()) {
                Log.w(TAG, "Store incomplete: " + f.getName() + " missing.");
                return null;
            }
            try {
                byte[] bytes = new byte[(int) f.length()];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                    int read = 0;
                    while (read < bytes.length) {
                        int n = fis.read(bytes, read, bytes.length - read);
                        if (n < 0) break;
                        read += n;
                    }
                }
                tablas.put(t[1], Tabla.fromCsv(t[1], new String(bytes, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                Log.e(TAG, "Error reading " + f.getName(), e);
                return null;
            }
        }
        return tablas;
    }

    /**
     * Writes the whole store.
     *
     * <p>Every table goes to a ".tmp" sibling first and only then is renamed into place, so an
     * interrupted or failed write can never leave the store half-updated — a técnico whose
     * Tecnicos table was replaced while Productos was not would be locked into a mismatched
     * database with no way back.
     *
     * @return true only if all four tables are now in place.
     */
    static boolean save(Context context, Map<String, Tabla> tablas) {
        if (tablas == null) return false;
        File dir = dir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Could not create the store directory.");
            return false;
        }

        File[] temps = new File[TABLES.length];
        try {
            for (int i = 0; i < TABLES.length; i++) {
                Tabla tabla = tablas.get(TABLES[i][1]);
                if (tabla == null) {
                    Log.e(TAG, "Refusing to save: table '" + TABLES[i][1] + "' is missing.");
                    return false;
                }
                File tmp = new File(dir, TABLES[i][0] + ".csv.tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(tabla.toCsv().getBytes(StandardCharsets.UTF_8));
                }
                temps[i] = tmp;
            }

            for (int i = 0; i < TABLES.length; i++) {
                File target = file(context, TABLES[i][0]);
                if (target.exists() && !target.delete()) {
                    Log.w(TAG, "Could not remove the previous " + target.getName());
                }
                if (!temps[i].renameTo(target)) {
                    throw new IOException("Rename failed for " + target.getName());
                }
                temps[i] = null;
            }
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error saving the Datos App store", e);
            return false;
        } finally {
            for (File tmp : temps) {
                if (tmp != null && tmp.exists() && !tmp.delete()) {
                    Log.w(TAG, "Left behind a temp file: " + tmp.getName());
                }
            }
        }
    }

    /**
     * Converts an imported workbook into the four tables. Returns null when a sheet is missing,
     * which the caller reports exactly as the old "falta la hoja requerida" validation did.
     *
     * <p>The workbook is only read here, never retained: the caller closes it immediately, which
     * is what keeps the import from holding a second copy of the data in POI's model.
     */
    static Map<String, Tabla> fromWorkbook(XSSFWorkbook wb) {
        if (wb == null) return null;
        Map<String, Tabla> tablas = new LinkedHashMap<>();
        for (String[] t : TABLES) {
            if (wb.getSheet(t[1]) == null) {
                Log.w(TAG, "Imported workbook has no '" + t[1] + "' sheet.");
                return null;
            }
            tablas.put(t[1], Tabla.fromSheet(t[1], wb.getSheet(t[1])));
        }
        return tablas;
    }
}
