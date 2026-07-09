package io.github.agudelojm.remisionesconagro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-scoped, in-memory draft for an in-progress remisión, keyed by técnico número.
 *
 * Survives navigating away from the remisión screen (Activity destroyed) but NOT an app close,
 * since static state dies with the process. Cleared on a successful send; kept on failure or when
 * the técnico goes back, so the remisión can be regenerated and resent (e.g. after a mail-app error).
 *
 * Replaces the previous JSON drafts, which existed only for the old rigid inventory check and now
 * leaked stale client data across app restarts.
 */
public class RemisionDraftCache {

    private static final Map<String, Draft> drafts = new HashMap<>();

    public static class Draft {
        public Map<String, String> finca;                    // resolved client, or null when anonymous
        public List<Map<String, String>> items = new ArrayList<>();
        public Map<String, String> tempClientData;           // new-client form data, or null
    }

    /** Returns the live draft for this técnico, or null if none is cached. */
    public static Draft get(String tecnicoNumero) {
        if (tecnicoNumero == null) return null;
        return drafts.get(tecnicoNumero);
    }

    /** Saves/updates the remisión work (finca + items) for this técnico. Stores defensive copies. */
    public static void saveRemision(String tecnicoNumero, Map<String, String> finca, List<Map<String, String>> items) {
        if (tecnicoNumero == null) return;
        Draft d = drafts.get(tecnicoNumero);
        if (d == null) {
            d = new Draft();
            drafts.put(tecnicoNumero, d);
        }
        d.finca = (finca != null) ? new HashMap<>(finca) : null;
        d.items = new ArrayList<>();
        if (items != null) {
            for (Map<String, String> m : items) {
                if (m != null) d.items.add(new HashMap<>(m));
            }
        }
    }

    /** Saves/updates the new-client form data for this técnico. Stores a defensive copy. */
    public static void saveTempClient(String tecnicoNumero, Map<String, String> tempClientData) {
        if (tecnicoNumero == null) return;
        Draft d = drafts.get(tecnicoNumero);
        if (d == null) {
            d = new Draft();
            drafts.put(tecnicoNumero, d);
        }
        d.tempClientData = (tempClientData != null) ? new HashMap<>(tempClientData) : null;
    }

    /** Clears the draft after a successful send. */
    public static void clear(String tecnicoNumero) {
        if (tecnicoNumero == null) return;
        drafts.remove(tecnicoNumero);
    }
}
