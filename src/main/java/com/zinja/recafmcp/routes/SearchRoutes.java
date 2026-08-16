package com.zinja.recafmcp.routes;

import com.google.gson.JsonObject;
import com.zinja.recafmcp.http.JsonResponses;
import com.zinja.recafmcp.http.McpHttpServer;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class SearchRoutes {
    private final WorkspaceManager wm;
    @SuppressWarnings("unused")
    private final SearchService searchService;

    public SearchRoutes(WorkspaceManager wm, SearchService searchService) {
        this.wm = wm;
        this.searchService = searchService;
    }

    public void register(McpHttpServer server) {
        server.get("/search/classes", (req, res) -> {
            Workspace ws = wm.getCurrent();
            if (ws == null) {
                res.json(JsonResponses.error("no workspace open"));
                return;
            }

            Matcher matcher = Matcher.of(req.query("pattern", ""), req.queryBool("regex", false), req.queryBool("case_sensitive", true));
            List<String> names = ws.jvmClassesStream()
                    .map(ClassPathNode::getValue)
                    .map(ClassInfo::getName)
                    .map(name -> name.replace('/', '.'))
                    .filter(matcher::matches)
                    .sorted()
                    .toList();

            JsonObject out = JsonResponses.paginated(names, "classes", req.queryInt("offset", 0), req.queryInt("limit", 0),
                    name -> {
                        JsonObject item = new JsonObject();
                        item.addProperty("name", name);
                        return item;
                    });
            res.json(out);
        });

        server.get("/search/members", (req, res) -> {
            Workspace ws = wm.getCurrent();
            if (ws == null) {
                res.json(JsonResponses.error("no workspace open"));
                return;
            }

            Matcher matcher = Matcher.of(req.query("pattern", ""), req.queryBool("regex", false), req.queryBool("case_sensitive", true));
            String kind = req.query("kind", "any");
            List<JsonObject> hits = new ArrayList<>();

            ws.jvmClassesStream().forEach(node -> {
                JvmClassInfo cls = (JvmClassInfo) node.getValue();
                if (kind.equals("any") || kind.equals("method")) {
                    for (MethodMember method : cls.getMethods()) {
                        if (matcher.matches(method.getName()))
                            hits.add(memberHit("method", cls, method.getName(), method.getDescriptor()));
                    }
                }
                if (kind.equals("any") || kind.equals("field")) {
                    for (FieldMember field : cls.getFields()) {
                        if (matcher.matches(field.getName()))
                            hits.add(memberHit("field", cls, field.getName(), field.getDescriptor()));
                    }
                }
            });

            JsonObject out = JsonResponses.paginated(hits, "members", req.queryInt("offset", 0), req.queryInt("limit", 0), hit -> hit);
            res.json(out);
        });

        server.get("/search/strings", (req, res) -> {
            Workspace ws = wm.getCurrent();
            if (ws == null) {
                res.json(JsonResponses.error("no workspace open"));
                return;
            }

            Matcher matcher = Matcher.of(req.query("pattern", ""), req.queryBool("regex", false), req.queryBool("case_sensitive", true));
            List<JsonObject> hits = SearchBytecodeSupport.searchStrings(ws, matcher::matches);
            JsonObject out = JsonResponses.paginated(hits, "hits", req.queryInt("offset", 0), req.queryInt("limit", 0), hit -> hit);
            res.json(out);
        });

        server.get("/search/numbers", (req, res) -> {
            Workspace ws = wm.getCurrent();
            if (ws == null) {
                res.json(JsonResponses.error("no workspace open"));
                return;
            }

            String value = req.query("value", "").trim();
            if (value.isEmpty()) {
                res.status(400).json(JsonResponses.error("missing 'value'"));
                return;
            }

            List<JsonObject> hits = SearchBytecodeSupport.searchNumbers(ws, value);
            JsonObject out = JsonResponses.paginated(hits, "hits", req.queryInt("offset", 0), req.queryInt("limit", 0), hit -> hit);
            res.json(out);
        });

        server.get("/search/instructions", (req, res) -> {
            Workspace ws = wm.getCurrent();
            if (ws == null) {
                res.json(JsonResponses.error("no workspace open"));
                return;
            }

            List<JsonObject> hits = SearchBytecodeSupport.searchInstructions(
                    ws,
                    req.query("opcode", ""),
                    req.query("operand", ""),
                    req.query("class_filter", ""));
            JsonObject out = JsonResponses.paginated(hits, "hits", req.queryInt("offset", 0), req.queryInt("limit", 0), hit -> hit);
            res.json(out);
        });
    }

    private static JsonObject memberHit(String kind, JvmClassInfo cls, String name, String descriptor) {
        JsonObject item = new JsonObject();
        item.addProperty("kind", kind);
        item.addProperty("owner", cls.getName().replace('/', '.'));
        item.addProperty("name", name);
        item.addProperty("descriptor", descriptor);
        return item;
    }

    private static final class Matcher {
        private final Pattern pattern;
        private final String needle;
        private final boolean caseSensitive;

        private Matcher(Pattern pattern, String needle, boolean caseSensitive) {
            this.pattern = pattern;
            this.needle = needle;
            this.caseSensitive = caseSensitive;
        }

        static Matcher of(String text, boolean regex, boolean caseSensitive) {
            if (regex) {
                return new Matcher(Pattern.compile(text, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE), null, caseSensitive);
            }
            return new Matcher(null, caseSensitive ? text : text.toLowerCase(), caseSensitive);
        }

        boolean matches(String input) {
            if (input == null) return false;
            if (pattern != null) return pattern.matcher(input).find();
            return (caseSensitive ? input : input.toLowerCase()).contains(needle);
        }
    }
}
