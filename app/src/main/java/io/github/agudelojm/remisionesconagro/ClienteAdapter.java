package io.github.agudelojm.remisionesconagro;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Autocomplete adapter for the client field in Ingresar Remisión.
 *
 * Searches across persona_doc (prefix) / persona_nombre / finca_nombre (contains), accent- and
 * case-insensitive, and DISPLAYS each suggestion as "persona_nombre - finca_nombre". The técnico
 * commits a client only by tapping a suggestion (the activity pins its finca_id); typing never
 * auto-selects.
 *
 * Performance: the normalized search keys are computed ONCE at construction, so each keystroke only
 * normalizes the query and runs cheap startsWith/contains — no per-row accent normalization, and the
 * result list is capped so the dropdown stays light.
 */
public class ClienteAdapter extends ArrayAdapter<Map<String, String>> {

    private static final int MAX_SUGGESTIONS = 50;

    private final List<Map<String, String>> todos;
    private final String[] docNorm;
    private final String[] nombreNorm;
    private final String[] fincaNorm;
    private List<Map<String, String>> filtrados = new ArrayList<>();

    private final Filter filter = new Filter() {
        @Override
        @SuppressWarnings("unchecked")
        public CharSequence convertResultToString(Object resultValue) {
            if (resultValue instanceof Map) {
                return ((Map<String, String>) resultValue).getOrDefault("persona_doc", "");
            }
            return super.convertResultToString(resultValue);
        }

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<Map<String, String>> out = new ArrayList<>();
            String q = Methods.normalizar(constraint == null ? "" : constraint.toString());
            if (!q.isEmpty()) {
                for (int i = 0; i < todos.size() && out.size() < MAX_SUGGESTIONS; i++) {
                    if (docNorm[i].startsWith(q) || nombreNorm[i].contains(q) || fincaNorm[i].contains(q)) {
                        out.add(todos.get(i));
                    }
                }
            }
            FilterResults r = new FilterResults();
            r.values = out;
            r.count = out.size();
            return r;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            filtrados = (results != null && results.values instanceof List)
                    ? (List<Map<String, String>>) results.values
                    : new ArrayList<>();
            notifyDataSetChanged();
        }
    };

    public ClienteAdapter(Context context, List<Map<String, String>> todos) {
        super(context, android.R.layout.simple_dropdown_item_1line);
        this.todos = (todos != null) ? todos : new ArrayList<>();
        int n = this.todos.size();
        docNorm = new String[n];
        nombreNorm = new String[n];
        fincaNorm = new String[n];
        for (int i = 0; i < n; i++) {
            Map<String, String> c = this.todos.get(i);
            docNorm[i] = Methods.normalizar(c.get("persona_doc"));
            nombreNorm[i] = Methods.normalizar(c.get("persona_nombre"));
            fincaNorm[i] = Methods.normalizar(c.get("finca_nombre"));
        }
    }

    @Override
    public int getCount() {
        return filtrados.size();
    }

    @Override
    public Map<String, String> getItem(int position) {
        return filtrados.get(position);
    }

    @Override
    public Filter getFilter() {
        return filter;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView tv = (convertView instanceof TextView)
                ? (TextView) convertView
                : (TextView) LayoutInflater.from(getContext())
                        .inflate(android.R.layout.simple_dropdown_item_1line, parent, false);

        Map<String, String> c = getItem(position);
        String pnom = c.getOrDefault("persona_nombre", "");
        String fnom = c.getOrDefault("finca_nombre", "");
        String label;
        if (pnom.isEmpty()) {
            label = fnom;
        } else if (fnom.isEmpty()) {
            label = pnom;
        } else {
            label = pnom + " - " + fnom;
        }
        tv.setText(label);
        return tv;
    }
}
