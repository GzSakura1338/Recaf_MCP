package com.zinja.recafmcp.routes;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

final class RefactorSupport {
    private RefactorSupport() {
    }

    static int renameLocalVariables(Workspace ws, RouteSupport.MethodTarget target, String variableName, String newName, int index) {
        ClassNode classNode = new ClassNode();
        new ClassReader(target.classInfo().getBytecode()).accept(classNode, 0);

        int renamed = 0;
        for (MethodNode method : classNode.methods) {
            if (!method.name.equals(target.name()) || !method.desc.equals(target.descriptor()) || method.localVariables == null)
                continue;
            for (LocalVariableNode local : method.localVariables) {
                if (!local.name.equals(variableName)) continue;
                if (index >= 0 && local.index != index) continue;
                local.name = newName;
                renamed++;
            }
            break;
        }
        if (renamed == 0) return 0;

        JvmClassBundle bundle = findBundle(ws, target.owner());
        if (bundle == null) return 0;

        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        JvmClassInfo updated = target.classInfo().toJvmClassBuilder().withBytecode(writer.toByteArray()).build();
        bundle.put(updated);
        return renamed;
    }

    static MappingFileFormat resolveFormat(MappingFormatManager manager, String input) {
        MappingFileFormat exact = manager.createFormatInstance(input);
        if (exact != null) return exact;

        String needle = normalize(input);
        return manager.getMappingFileFormats().stream()
                .filter(key -> normalize(key).equals(needle))
                .findFirst()
                .map(manager::createFormatInstance)
                .orElse(null);
    }

    static String remapPackage(String className, String oldPrefix, String newPrefix) {
        String suffix = className.substring(oldPrefix.length()).replaceFirst("^/", "");
        if (newPrefix.isEmpty()) return suffix;
        return suffix.isEmpty() ? newPrefix : newPrefix + "/" + suffix;
    }

    static String packagePrefix(String name) {
        return RouteSupport.internal(name).replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static JvmClassBundle findBundle(Workspace ws, String className) {
        return RouteSupport.primaryBundles(ws.getPrimaryResource())
                .filter(bundle -> bundle.containsKey(className))
                .findFirst()
                .orElse(null);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[^a-zA-Z0-9]+", "").toLowerCase();
    }
}
