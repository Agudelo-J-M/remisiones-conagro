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

/**
 * Autocomplete adapter for any list of "<código> - nombre" entries: the producto references and
 * the finca list, which {@link Methods#getListaProductos} and {@link Methods#getListaFincas} both
 * produce in that shape.
 *
 * <p>Replaces the plain {@code ArrayAdapter<String>} these screens used to build. That adapter's
 * default filter walks every entry on every keystroke and lowercases as it goes — fine for the 392
 * productos this app shipped against, much less so now the server sends 4 863. This one normalizes
 * once at construction, matches the reference by prefix and the name by substring (accent- and
 * case-insensitive, like the client search), and returns every match — see performFiltering for why
 * that is safe now that the keys are precomputed.
 *
 * <p>Entries keep the "Ref - Nombre" shape the rest of the flow expects, because
 * {@link Methods#buscarItem} splits the reference back off on " - ".
 */
public class CodigoNombreAdapter extends ArrayAdapter<String> {

    private final List<String> todos;
    /** Normalized "ref" and "nombre" halves, parallel to {@link #todos}. */
    private final String[] refNorm;
    private final String[] nombreNorm;
    private List<String> filtrados = new ArrayList<>();

    private final Filter filter = new Filter() {
        @Override
        public CharSequence convertResultToString(Object resultValue) {
            return resultValue == null ? "" : resultValue.toString();
        }

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<String> out = new ArrayList<>();
            String q = Methods.normalizar(constraint == null ? "" : constraint.toString());
            if (!q.isEmpty()) {
                // EVERY match is returned, not a capped slice. The fields set
                // completionThreshold="1", so a one-character query matches hundreds of productos;
                // truncating would silently hide the técnico's item and leave no way to reach it
                // except guessing more characters. It can be exhaustive because the keys are
                // normalized once at construction, so a pass over all 4 800 entries is a few
                // thousand cheap startsWith/contains calls, and the dropdown recycles its rows.
                //
                // Reference prefixes come first because that is what the técnico types or scans;
                // name matches follow, so the likeliest row is at the top either way.
                for (int i = 0; i < todos.size(); i++) {
                    if (refNorm[i].startsWith(q)) out.add(todos.get(i));
                }
                for (int i = 0; i < todos.size(); i++) {
                    if (!refNorm[i].startsWith(q) && nombreNorm[i].contains(q)) out.add(todos.get(i));
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
                    ? (List<String>) results.values
                    : new ArrayList<>();
            notifyDataSetChanged();
        }
    };

    /** @param entradas "Ref - Nombre" strings, i.e. what {@link Methods#getListaProductos} returns. */
    public CodigoNombreAdapter(Context context, List<String> entradas) {
        super(context, android.R.layout.simple_dropdown_item_1line);
        this.todos = (entradas != null) ? entradas : new ArrayList<>();
        int n = this.todos.size();
        refNorm = new String[n];
        nombreNorm = new String[n];
        for (int i = 0; i < n; i++) {
            String entry = this.todos.get(i);
            int sep = entry.indexOf(" - ");
            refNorm[i] = Methods.normalizar(sep == -1 ? entry : entry.substring(0, sep));
            nombreNorm[i] = Methods.normalizar(sep == -1 ? "" : entry.substring(sep + 3));
        }
    }

    @Override
    public int getCount() {
        return filtrados.size();
    }

    @Override
    public String getItem(int position) {
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
        tv.setText(getItem(position));
        return tv;
    }
}
