package com.wygtv.tv;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class XtreamProtocol {
    static boolean validLogin(String host, String username, String password) {
        try {
            URI uri = new URI(host);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && !username.trim().isEmpty() && !password.isEmpty();
        } catch (Exception ignored) { return false; }
    }

    static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    /** Lower-cases, strips accents and punctuation so "BBC One HD" matches "bbc one" and "Télé" matches "tele". */
    static String searchKey(String value) {
        if (value == null) return "";
        String plain = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /**
     * Ranks a channel name (already passed through searchKey) against a query (also searchKey'd).
     * 0 = name starts with the query, 1 = every query word appears somewhere in the name, -1 = no match.
     */
    static int searchRank(String nameKey, String queryKey) {
        if (queryKey.isEmpty()) return -1;
        if (nameKey.startsWith(queryKey)) return 0;
        for (String word : queryKey.split(" ")) if (!nameKey.contains(word)) return -1;
        return 1;
    }

    /** Titles whose name starts with the query come first, then names that contain every word. */
    static org.json.JSONArray filter(org.json.JSONArray all, String[] nameKeys, String queryKey) {
        java.util.List<org.json.JSONObject> starts = new java.util.ArrayList<>(), contains = new java.util.ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            org.json.JSONObject it = all.optJSONObject(i);
            if (it == null) continue;
            int rank = XtreamProtocol.searchRank(i < nameKeys.length ? nameKeys[i] : XtreamProtocol.searchKey(it.optString("name")), queryKey);
            if (rank == 0) starts.add(it); else if (rank > 0) contains.add(it);
        }
        org.json.JSONArray out = new org.json.JSONArray();
        for (org.json.JSONObject o : starts) out.put(o);
        for (org.json.JSONObject o : contains) out.put(o);
        return out;
    }

}
