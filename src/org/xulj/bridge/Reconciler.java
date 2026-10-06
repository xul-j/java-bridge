package org.xulj.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Diffs successive virtual trees and produces the minimal XUL-J ops. Order matters for the
 * client: commands, removals, updates (so `order` is current), inserts in pre-order, row data.
 * Same algorithm as the .NET bridge's Reconciler.
 */
public final class Reconciler {
    private Map<String, VNode> prev = new HashMap<>();
    private Map<String, String> prevAttrs = new HashMap<>();
    private Map<String, String> prevCommands = new HashMap<>();
    private Map<String, List<String>> prevRows = new HashMap<>();

    private static Map<String, Object> op(String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", kind);
        return m;
    }

    public List<Map<String, Object>> diff(List<VNode> nodes, Map<String, Map<String, Object>> commands) {
        List<Map<String, Object>> ops = new ArrayList<>();
        Map<String, VNode> next = new HashMap<>();
        for (VNode n : nodes) next.put(n.id, n);

        Map<String, String> nextCommands = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : commands.entrySet()) {
            String json = Json.write(e.getValue());
            nextCommands.put(e.getKey(), json);
            if (json.equals(prevCommands.get(e.getKey()))) continue;
            Map<String, Object> o = op("command");
            o.put("id", e.getKey());
            o.putAll(e.getValue());
            ops.add(o);
        }
        for (String id : prevCommands.keySet()) {
            if (nextCommands.containsKey(id)) continue;
            Map<String, Object> o = op("command");
            o.put("id", id);
            o.put("deleted", true);
            ops.add(o);
        }

        Set<String> removed = new HashSet<>();
        for (VNode old : prev.values()) {
            VNode now = next.get(old.id);
            if (now == null || !now.parent.equals(old.parent)) removed.add(old.id);
        }
        for (String id : removed) {
            String parent = prev.get(id).parent;
            if (removed.contains(parent)) continue; // the client removes the subtree with it
            Map<String, Object> o = op("remove");
            o.put("id", id);
            ops.add(o);
        }

        Map<String, String> nextAttrs = new HashMap<>();
        List<Map<String, Object>> inserts = new ArrayList<>();
        for (VNode n : nodes) {
            Map<String, Object> attrs = new LinkedHashMap<>(n.attrs);
            attrs.put("order", n.order);
            if (n.tag.equals("tree")) {
                Map<String, Object> src = new LinkedHashMap<>();
                src.put("source", n.id);
                attrs.put("rows", src);
            }
            String json = Json.write(attrs);
            nextAttrs.put(n.id, json);
            VNode old = prev.get(n.id);
            boolean existed = old != null && !removed.contains(n.id) && !ancestorRemoved(old, removed);
            if (!existed) {
                Map<String, Object> o = op("node");
                o.put("in", n.parent);
                o.put("id", n.id);
                o.put("tag", n.tag);
                o.putAll(attrs);
                inserts.add(o);
            } else if (!old.tag.equals(n.tag)) {
                Map<String, Object> o = op("replace");
                o.put("id", n.id);
                o.put("tag", n.tag);
                o.putAll(attrs);
                ops.add(o);
            } else if (!json.equals(prevAttrs.get(n.id))) {
                Map<String, Object> o = op("set");
                o.put("id", n.id);
                o.put("attrs", attrs);
                o.put("replace", true);
                ops.add(o);
            }
        }
        ops.addAll(inserts);

        Map<String, List<String>> nextRows = new HashMap<>();
        for (VNode n : nodes) {
            if (n.rows == null) continue;
            List<String> rows = new ArrayList<>();
            for (Map<String, Object> r : n.rows) rows.add(Json.write(r));
            nextRows.put(n.id, rows);
            List<String> old = prevRows.get(n.id);
            boolean prefix = old != null && !removed.contains(n.id) && old.size() <= rows.size()
                && rows.subList(0, old.size()).equals(old);
            Map<String, Object> o = op("rows");
            o.put("source", n.id);
            if (prefix) {
                if (rows.size() == old.size()) continue;
                o.put("append", new ArrayList<>(n.rows.subList(old.size(), rows.size())));
            } else {
                o.put("clear", true);
                o.put("append", n.rows);
            }
            ops.add(o);
        }
        for (String id : prevRows.keySet()) {
            if (nextRows.containsKey(id)) continue;
            Map<String, Object> o = op("rows");
            o.put("source", id);
            o.put("clear", true);
            ops.add(o);
        }

        prev = next;
        prevAttrs = nextAttrs;
        prevCommands = nextCommands;
        prevRows = nextRows;
        return ops;
    }

    private boolean ancestorRemoved(VNode n, Set<String> removed) {
        for (String p = n.parent; p != null && prev.containsKey(p); p = prev.get(p).parent) {
            if (removed.contains(p)) return true;
        }
        return false;
    }
}
