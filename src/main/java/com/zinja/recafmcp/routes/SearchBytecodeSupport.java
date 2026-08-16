package com.zinja.recafmcp.routes;

import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class SearchBytecodeSupport {
    private SearchBytecodeSupport() {
    }

    static List<JsonObject> searchStrings(Workspace ws, Predicate<String> matcher) {
        List<JsonObject> hits = new ArrayList<>();
        for (ClassPathNode node : ws.jvmClassesStream().toList()) {
            JvmClassInfo cls = (JvmClassInfo) node.getValue();
            visitMethods(cls, (method, index, line, insn) -> {
                if (insn instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof String value && matcher.test(value)) {
                    hits.add(SearchInsnSupport.hit(cls, method, index, line).with("value", value).json());
                }
            });
        }
        return hits;
    }

    static List<JsonObject> searchNumbers(Workspace ws, String queryValue) {
        List<JsonObject> hits = new ArrayList<>();
        for (ClassPathNode node : ws.jvmClassesStream().toList()) {
            JvmClassInfo cls = (JvmClassInfo) node.getValue();
            visitMethods(cls, (method, index, line, insn) -> {
                Number value = SearchInsnSupport.constantValue(insn);
                if (value != null && SearchInsnSupport.numberMatches(queryValue, value)) {
                    hits.add(SearchInsnSupport.hit(cls, method, index, line)
                            .with("value", value.toString())
                            .with("value_type", value.getClass().getSimpleName())
                            .json());
                }
            });
        }
        return hits;
    }

    static List<JsonObject> searchInstructions(Workspace ws, String opcode, String operand, String classFilter) {
        List<JsonObject> hits = new ArrayList<>();
        String opcodeNeedle = SearchInsnSupport.normalize(opcode);
        String operandNeedle = SearchInsnSupport.normalize(operand);
        String classNeedle = classFilter == null ? "" : classFilter.trim();

        for (ClassPathNode node : ws.jvmClassesStream().toList()) {
            JvmClassInfo cls = (JvmClassInfo) node.getValue();
            if (!classNeedle.isEmpty() && !RouteSupport.external(cls.getName()).startsWith(classNeedle)) continue;

            visitMethods(cls, (method, index, line, insn) -> {
                String opcodeName = SearchInsnSupport.opcodeName(insn);
                if (opcodeName.isEmpty()) return;
                if (!opcodeNeedle.isEmpty() && !opcodeName.equals(opcodeNeedle)) return;

                String operandText = SearchInsnSupport.operandText(insn);
                if (!operandNeedle.isEmpty() && !SearchInsnSupport.normalize(operandText).contains(operandNeedle))
                    return;

                hits.add(SearchInsnSupport.hit(cls, method, index, line)
                        .with("opcode", opcodeName)
                        .with("operand", operandText)
                        .json());
            });
        }
        return hits;
    }

    private static void visitMethods(JvmClassInfo cls, InstructionVisitor visitor) {
        ClassNode classNode = new ClassNode();
        new ClassReader(cls.getBytecode()).accept(classNode, 0);

        for (MethodNode method : classNode.methods) {
            int line = -1;
            for (int i = 0; i < method.instructions.size(); i++) {
                AbstractInsnNode insn = method.instructions.get(i);
                if (insn instanceof LineNumberNode lineNode) {
                    line = lineNode.line;
                    continue;
                }
                visitor.visit(method, i, line, insn);
            }
        }
    }

    private interface InstructionVisitor {
        void visit(MethodNode method, int index, int line, AbstractInsnNode insn);
    }
}
