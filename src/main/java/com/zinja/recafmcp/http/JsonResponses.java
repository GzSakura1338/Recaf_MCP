package com.zinja.recafmcp.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.function.Function;

/**
 * Shared JSON response builders.
 */
public final class JsonResponses {
    private JsonResponses() {
    }

    public static JsonObject error(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message);
        return obj;
    }

    public static JsonObject ok() {
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", true);
        return obj;
    }

    public static JsonObject ok(String message) {
        JsonObject obj = ok();
        obj.addProperty("message", message);
        return obj;
    }

    /**
     * Build a paginated response envelope that matches the Python
     * {@code PaginationUtils} shape.
     *
     * @param fullList entire result set (the method slices it by offset/limit)
     * @param itemsKey name of the flat array field (e.g. "classes", "methods")
     * @param offset   requested offset
     * @param limit    requested limit — 0 means "return everything from offset"
     */
    public static <T> JsonObject paginated(List<T> fullList,
                                           String itemsKey,
                                           int offset,
                                           int limit,
                                           Function<T, Object> serializer) {
        int total = fullList.size();
        int from = Math.max(0, Math.min(offset, total));
        int to = limit <= 0 ? total : Math.min(from + limit, total);
        List<T> slice = fullList.subList(from, to);

        JsonArray items = new JsonArray();
        for (T item : slice) {
            Object serialized = serializer.apply(item);
            if (serialized instanceof com.google.gson.JsonElement el) {
                items.add(el);
            } else {
                items.add(String.valueOf(serialized));
            }
        }

        JsonObject pagination = new JsonObject();
        pagination.addProperty("total", total);
        pagination.addProperty("offset", from);
        pagination.addProperty("limit", limit);
        pagination.addProperty("count", slice.size());
        pagination.addProperty("has_more", to < total);

        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", "paginated-list");
        envelope.add(itemsKey, items);
        envelope.add("items", items);
        envelope.add("pagination", pagination);
        return envelope;
    }
}
