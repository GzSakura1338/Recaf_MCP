package com.zinja.recafmcp.routes;

import com.zinja.recafmcp.http.JsonResponses;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.LocalVariable;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class RouteSupport {
    private RouteSupport() {
    }

    static Workspace requireWorkspace(WorkspaceManager wm, com.zinja.recafmcp.http.Response res) throws Exception {
        Workspace ws = wm.getCurrent();
        if (ws != null) return ws;
        res.status(409).json(JsonResponses.error("no workspace open"));
        return null;
    }

    static JvmClassInfo requireClass(Workspace ws, String className, com.zinja.recafmcp.http.Response res) throws Exception {
        ClassPathNode node = ws.findJvmClass(internal(className));
        if (node != null) return (JvmClassInfo) node.getValue();
        res.status(404).json(JsonResponses.error("class not found: " + className));
        return null;
    }

    static MethodTarget requireMethod(Workspace ws, String className, String methodName, String descriptor,
                                      com.zinja.recafmcp.http.Response res) throws Exception {
        if (required(className, "class_name", res) == null || required(methodName, "method_name", res) == null)
            return null;
        JvmClassInfo cls = requireClass(ws, className, res);
        if (cls == null) return null;

        List<MethodMember> matches = cls.getMethods().stream()
                .filter(method -> method.getName().equals(methodName))
                .filter(method -> descriptor == null || descriptor.isBlank() || descriptor.equals(method.getDescriptor()))
                .toList();
        if (matches.isEmpty()) {
            res.status(404).json(JsonResponses.error("method not found"));
            return null;
        }
        if (matches.size() > 1) {
            res.status(409).json(JsonResponses.error("method name is ambiguous; pass 'descriptor'"));
            return null;
        }

        MethodMember method = matches.get(0);
        return new MethodTarget(cls, cls.getName(), method.getName(), method.getDescriptor(), new ArrayList<>(method.getLocalVariables()));
    }

    static FieldTarget requireField(Workspace ws, String className, String fieldName, String descriptor,
                                    com.zinja.recafmcp.http.Response res) throws Exception {
        if (required(className, "class_name", res) == null || required(fieldName, "field_name", res) == null)
            return null;
        JvmClassInfo cls = requireClass(ws, className, res);
        if (cls == null) return null;

        List<FieldMember> matches = cls.getFields().stream()
                .filter(field -> field.getName().equals(fieldName))
                .filter(field -> descriptor == null || descriptor.isBlank() || descriptor.equals(field.getDescriptor()))
                .toList();
        if (matches.isEmpty()) {
            res.status(404).json(JsonResponses.error("field not found"));
            return null;
        }
        if (matches.size() > 1) {
            res.status(409).json(JsonResponses.error("field name is ambiguous; pass 'descriptor'"));
            return null;
        }

        FieldMember field = matches.get(0);
        return new FieldTarget(cls.getName(), field.getName(), field.getDescriptor());
    }

    static Stream<JvmClassBundle> primaryBundles(WorkspaceResource resource) {
        return Stream.concat(
                resource.jvmClassBundleStreamRecursive(),
                resource.versionedJvmClassBundleStreamRecursive().map(bundle -> (JvmClassBundle) bundle));
    }

    static String required(String value, String name, com.zinja.recafmcp.http.Response res) throws Exception {
        if (value == null || value.isBlank()) {
            res.status(400).json(JsonResponses.error("missing '" + name + "'"));
            return null;
        }
        return value;
    }

    static String internal(String name) {
        return name.replace('.', '/');
    }

    static String external(String name) {
        return name == null ? null : name.replace('/', '.');
    }

    record MethodTarget(JvmClassInfo classInfo, String owner, String name, String descriptor,
                        List<LocalVariable> locals) {
    }

    record FieldTarget(String owner, String name, String descriptor) {
    }
}
