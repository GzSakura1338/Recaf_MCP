package com.zinja.recafmcp.routes;

import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;
import software.coley.recaf.info.JvmClassInfo;

import java.math.BigDecimal;

final class SearchInsnSupport {
    private SearchInsnSupport() {
    }

    static Number constantValue(AbstractInsnNode insn) {
        return switch (insn.getOpcode()) {
            case Opcodes.ICONST_M1 -> -1;
            case Opcodes.ICONST_0 -> 0;
            case Opcodes.ICONST_1 -> 1;
            case Opcodes.ICONST_2 -> 2;
            case Opcodes.ICONST_3 -> 3;
            case Opcodes.ICONST_4 -> 4;
            case Opcodes.ICONST_5 -> 5;
            case Opcodes.LCONST_0 -> 0L;
            case Opcodes.LCONST_1 -> 1L;
            case Opcodes.FCONST_0 -> 0F;
            case Opcodes.FCONST_1 -> 1F;
            case Opcodes.FCONST_2 -> 2F;
            case Opcodes.DCONST_0 -> 0D;
            case Opcodes.DCONST_1 -> 1D;
            default -> fromInstruction(insn);
        };
    }

    static boolean numberMatches(String queryValue, Number value) {
        String trimmed = queryValue == null ? "" : queryValue.trim();
        if (trimmed.isEmpty()) return false;
        if (value instanceof Double d && (Double.isNaN(d) || Double.isInfinite(d)))
            return trimmed.equalsIgnoreCase(value.toString());
        if (value instanceof Float f && (Float.isNaN(f) || Float.isInfinite(f)))
            return trimmed.equalsIgnoreCase(value.toString());

        try {
            BigDecimal query = new BigDecimal(trimmed.replaceAll("[dDfFlL]$", ""));
            BigDecimal actual = new BigDecimal(value.toString());
            return query.compareTo(actual) == 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    static String opcodeName(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode < 0 || opcode >= Printer.OPCODES.length) return "";
        return Printer.OPCODES[opcode].toLowerCase();
    }

    static String operandText(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode methodInsn) {
            return RouteSupport.external(methodInsn.owner) + "." + methodInsn.name + methodInsn.desc;
        }
        if (insn instanceof FieldInsnNode fieldInsn) {
            return RouteSupport.external(fieldInsn.owner) + "." + fieldInsn.name + ":" + fieldInsn.desc;
        }
        if (insn instanceof LdcInsnNode ldcInsn) return String.valueOf(ldcInsn.cst);
        if (insn instanceof TypeInsnNode typeInsn) return typeInsn.desc;
        if (insn instanceof VarInsnNode varInsn) return Integer.toString(varInsn.var);
        if (insn instanceof IntInsnNode intInsn) return Integer.toString(intInsn.operand);
        if (insn instanceof IincInsnNode iincInsn) return iincInsn.var + "," + iincInsn.incr;
        if (insn instanceof InvokeDynamicInsnNode dynamicInsn) return dynamicInsn.name + dynamicInsn.desc;
        if (insn instanceof MultiANewArrayInsnNode arrayInsn) return arrayInsn.desc + "," + arrayInsn.dims;
        return "";
    }

    static HitBuilder hit(JvmClassInfo cls, MethodNode method, int index, int line) {
        return new HitBuilder()
                .with("class_name", RouteSupport.external(cls.getName()))
                .with("method_name", method.name)
                .with("descriptor", method.desc)
                .with("instruction_index", index)
                .withIf(line >= 0, "line_number", line);
    }

    static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase();
    }

    private static Number fromInstruction(AbstractInsnNode insn) {
        if (insn instanceof IntInsnNode intInsn) return intInsn.operand;
        if (insn instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof Number number) return number;
        return null;
    }

    static final class HitBuilder {
        private final JsonObject json = new JsonObject();

        HitBuilder with(String key, String value) {
            json.addProperty(key, value);
            return this;
        }

        HitBuilder with(String key, Number value) {
            json.addProperty(key, value);
            return this;
        }

        HitBuilder withIf(boolean condition, String key, Number value) {
            if (condition) json.addProperty(key, value);
            return this;
        }

        JsonObject json() {
            return json;
        }
    }
}
