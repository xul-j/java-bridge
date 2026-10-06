package org.xulj.bridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One element of the virtual XUL-J tree the Renderer builds each frame. */
public final class VNode {
    public final String id;
    public final String tag;
    public final String parent;
    public final int order;
    public final Map<String, Object> attrs = new LinkedHashMap<>();
    /** Row data when tag is "tree". */
    public List<Map<String, Object>> rows;

    public VNode(String id, String tag, String parent, int order) {
        this.id = id;
        this.tag = tag;
        this.parent = parent;
        this.order = order;
    }
}
