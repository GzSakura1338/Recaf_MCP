package com.zinja.recafmcp.routes;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Parses a simplified JASM-like text format into an InsnList + metadata.
 * <p>
 * Supported instruction formats (one per line):
 * ALOAD 0                           var-index
 * BIPUSH 10                         int operand
 * NEW java/lang/String              type reference (internal form)
 * GETFIELD com/example/C.field : I  field ref (owner/Name.fieldName : desc)
 * INVOKEVIRTUAL owner/Name.m (D)V   method ref (owner/Name.method desc)
 * GOTO L0                           jump target label
 * LDC "hello" or LDC 42 or LDC 3.14
 * IINC 0 1                          var-index increment
 * MULTIANEWARRAY [[Ljava/lang/String; 1
 * ICONST_0                          no-arg
 * RETURN                            no-arg
 * <p>
 * Structural:
 * LABEL name                        define an explicit label
 * LINE 42                           line-number hint
 * TRYCATCH Lstart Lend Lhandler type
 * <p>
 * Textifier directives (TRYCATCHBLOCK, MAXSTACK, MAXLOCALS, LOCALVARIABLE,
 * FRAME, F_SAME etc.) are silently skipped.
 * <p>
 * Lines starting with # or // are comments. Blank lines ignored.
 */
final class JasmSupport {

    private JasmSupport() {
    }

    static final class ParsedMethod {
        final InsnList instructions;
        final List<TryCatchBlockNode> tryCatchBlocks;

        ParsedMethod(InsnList instructions, List<TryCatchBlockNode> tryCatchBlocks) {
            this.instructions = instructions;
            this.tryCatchBlocks = tryCatchBlocks;
        }
    }

    // ── opcode name → int ─────────────────────────────────────────────

    static Integer opcode(String name) {
        return NAME_TO_OPCODE.get(name);
    }

    private static final Map<String, Integer> NAME_TO_OPCODE = new HashMap<>();

    static {
        // no-arg
        n("NOP", Opcodes.NOP);
        n("ACONST_NULL", Opcodes.ACONST_NULL);
        n("ICONST_M1", Opcodes.ICONST_M1);
        n("ICONST_0", Opcodes.ICONST_0);
        n("ICONST_1", Opcodes.ICONST_1);
        n("ICONST_2", Opcodes.ICONST_2);
        n("ICONST_3", Opcodes.ICONST_3);
        n("ICONST_4", Opcodes.ICONST_4);
        n("ICONST_5", Opcodes.ICONST_5);
        n("LCONST_0", Opcodes.LCONST_0);
        n("LCONST_1", Opcodes.LCONST_1);
        n("FCONST_0", Opcodes.FCONST_0);
        n("FCONST_1", Opcodes.FCONST_1);
        n("FCONST_2", Opcodes.FCONST_2);
        n("DCONST_0", Opcodes.DCONST_0);
        n("DCONST_1", Opcodes.DCONST_1);
        n("IALOAD", Opcodes.IALOAD);
        n("LALOAD", Opcodes.LALOAD);
        n("FALOAD", Opcodes.FALOAD);
        n("DALOAD", Opcodes.DALOAD);
        n("AALOAD", Opcodes.AALOAD);
        n("BALOAD", Opcodes.BALOAD);
        n("CALOAD", Opcodes.CALOAD);
        n("SALOAD", Opcodes.SALOAD);
        n("IASTORE", Opcodes.IASTORE);
        n("LASTORE", Opcodes.LASTORE);
        n("FASTORE", Opcodes.FASTORE);
        n("DASTORE", Opcodes.DASTORE);
        n("AASTORE", Opcodes.AASTORE);
        n("BASTORE", Opcodes.BASTORE);
        n("CASTORE", Opcodes.CASTORE);
        n("SASTORE", Opcodes.SASTORE);
        n("POP", Opcodes.POP);
        n("POP2", Opcodes.POP2);
        n("DUP", Opcodes.DUP);
        n("DUP_X1", Opcodes.DUP_X1);
        n("DUP_X2", Opcodes.DUP_X2);
        n("DUP2", Opcodes.DUP2);
        n("DUP2_X1", Opcodes.DUP2_X1);
        n("DUP2_X2", Opcodes.DUP2_X2);
        n("SWAP", Opcodes.SWAP);
        n("IADD", Opcodes.IADD);
        n("LADD", Opcodes.LADD);
        n("FADD", Opcodes.FADD);
        n("DADD", Opcodes.DADD);
        n("ISUB", Opcodes.ISUB);
        n("LSUB", Opcodes.LSUB);
        n("FSUB", Opcodes.FSUB);
        n("DSUB", Opcodes.DSUB);
        n("IMUL", Opcodes.IMUL);
        n("LMUL", Opcodes.LMUL);
        n("FMUL", Opcodes.FMUL);
        n("DMUL", Opcodes.DMUL);
        n("IDIV", Opcodes.IDIV);
        n("LDIV", Opcodes.LDIV);
        n("FDIV", Opcodes.FDIV);
        n("DDIV", Opcodes.DDIV);
        n("IREM", Opcodes.IREM);
        n("LREM", Opcodes.LREM);
        n("FREM", Opcodes.FREM);
        n("DREM", Opcodes.DREM);
        n("INEG", Opcodes.INEG);
        n("LNEG", Opcodes.LNEG);
        n("FNEG", Opcodes.FNEG);
        n("DNEG", Opcodes.DNEG);
        n("ISHL", Opcodes.ISHL);
        n("LSHL", Opcodes.LSHL);
        n("ISHR", Opcodes.ISHR);
        n("LSHR", Opcodes.LSHR);
        n("IUSHR", Opcodes.IUSHR);
        n("LUSHR", Opcodes.LUSHR);
        n("IAND", Opcodes.IAND);
        n("LAND", Opcodes.LAND);
        n("IOR", Opcodes.IOR);
        n("LOR", Opcodes.LOR);
        n("IXOR", Opcodes.IXOR);
        n("LXOR", Opcodes.LXOR);
        n("I2L", Opcodes.I2L);
        n("I2F", Opcodes.I2F);
        n("I2D", Opcodes.I2D);
        n("L2I", Opcodes.L2I);
        n("L2F", Opcodes.L2F);
        n("L2D", Opcodes.L2D);
        n("F2I", Opcodes.F2I);
        n("F2L", Opcodes.F2L);
        n("F2D", Opcodes.F2D);
        n("D2I", Opcodes.D2I);
        n("D2L", Opcodes.D2L);
        n("D2F", Opcodes.D2F);
        n("I2B", Opcodes.I2B);
        n("I2C", Opcodes.I2C);
        n("I2S", Opcodes.I2S);
        n("LCMP", Opcodes.LCMP);
        n("FCMPL", Opcodes.FCMPL);
        n("FCMPG", Opcodes.FCMPG);
        n("DCMPL", Opcodes.DCMPL);
        n("DCMPG", Opcodes.DCMPG);
        n("IRETURN", Opcodes.IRETURN);
        n("LRETURN", Opcodes.LRETURN);
        n("FRETURN", Opcodes.FRETURN);
        n("DRETURN", Opcodes.DRETURN);
        n("ARETURN", Opcodes.ARETURN);
        n("RETURN", Opcodes.RETURN);
        n("ARRAYLENGTH", Opcodes.ARRAYLENGTH);
        n("ATHROW", Opcodes.ATHROW);
        n("MONITORENTER", Opcodes.MONITORENTER);
        n("MONITOREXIT", Opcodes.MONITOREXIT);

        // var
        n("ILOAD", Opcodes.ILOAD);
        n("LLOAD", Opcodes.LLOAD);
        n("FLOAD", Opcodes.FLOAD);
        n("DLOAD", Opcodes.DLOAD);
        n("ALOAD", Opcodes.ALOAD);
        n("ISTORE", Opcodes.ISTORE);
        n("LSTORE", Opcodes.LSTORE);
        n("FSTORE", Opcodes.FSTORE);
        n("DSTORE", Opcodes.DSTORE);
        n("ASTORE", Opcodes.ASTORE);
        n("RET", Opcodes.RET);

        // int
        n("BIPUSH", Opcodes.BIPUSH);
        n("SIPUSH", Opcodes.SIPUSH);
        n("NEWARRAY", Opcodes.NEWARRAY);

        // type
        n("NEW", Opcodes.NEW);
        n("ANEWARRAY", Opcodes.ANEWARRAY);
        n("CHECKCAST", Opcodes.CHECKCAST);
        n("INSTANCEOF", Opcodes.INSTANCEOF);

        // field
        n("GETFIELD", Opcodes.GETFIELD);
        n("PUTFIELD", Opcodes.PUTFIELD);
        n("GETSTATIC", Opcodes.GETSTATIC);
        n("PUTSTATIC", Opcodes.PUTSTATIC);

        // method
        n("INVOKEVIRTUAL", Opcodes.INVOKEVIRTUAL);
        n("INVOKESPECIAL", Opcodes.INVOKESPECIAL);
        n("INVOKESTATIC", Opcodes.INVOKESTATIC);
        n("INVOKEINTERFACE", Opcodes.INVOKEINTERFACE);

        // jump
        n("IFEQ", Opcodes.IFEQ);
        n("IFNE", Opcodes.IFNE);
        n("IFLT", Opcodes.IFLT);
        n("IFGE", Opcodes.IFGE);
        n("IFGT", Opcodes.IFGT);
        n("IFLE", Opcodes.IFLE);
        n("IF_ICMPEQ", Opcodes.IF_ICMPEQ);
        n("IF_ICMPNE", Opcodes.IF_ICMPNE);
        n("IF_ICMPLT", Opcodes.IF_ICMPLT);
        n("IF_ICMPGE", Opcodes.IF_ICMPGE);
        n("IF_ICMPGT", Opcodes.IF_ICMPGT);
        n("IF_ICMPLE", Opcodes.IF_ICMPLE);
        n("IF_ACMPEQ", Opcodes.IF_ACMPEQ);
        n("IF_ACMPNE", Opcodes.IF_ACMPNE);
        n("GOTO", Opcodes.GOTO);
        n("JSR", Opcodes.JSR);
        n("IFNULL", Opcodes.IFNULL);
        n("IFNONNULL", Opcodes.IFNONNULL);

        // ldc / iinc / multianewarray
        n("LDC", Opcodes.LDC);
        n("IINC", Opcodes.IINC);
        n("MULTIANEWARRAY", Opcodes.MULTIANEWARRAY);
    }

    private static void n(String name, int op) {
        NAME_TO_OPCODE.put(name, op);
    }

    private static final Set<Integer> VAR_OPS = Set.of(
            Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD,
            Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE, Opcodes.RET);
    private static final Set<Integer> INT_OPS = Set.of(Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.NEWARRAY);
    private static final Set<Integer> TYPE_OPS = Set.of(Opcodes.NEW, Opcodes.ANEWARRAY, Opcodes.CHECKCAST, Opcodes.INSTANCEOF);
    private static final Set<Integer> FIELD_OPS = Set.of(Opcodes.GETFIELD, Opcodes.PUTFIELD, Opcodes.GETSTATIC, Opcodes.PUTSTATIC);
    private static final Set<Integer> METHOD_OPS = Set.of(Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE);
    private static final Set<Integer> JUMP_OPS = Set.of(
            Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
            Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
            Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
            Opcodes.GOTO, Opcodes.JSR, Opcodes.IFNULL, Opcodes.IFNONNULL);

    // ── main parse ────────────────────────────────────────────────────

    static ParsedMethod parse(String text) {
        List<String> lines = preprocess(text);
        InsnList insns = new InsnList();
        List<TryCatchBlockNode> tryCatches = new ArrayList<>();
        Map<String, LabelNode> labelMap = new LinkedHashMap<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) continue;
            if (tryHandleLabel(line, labelMap)) continue;
            if (tryHandleTryCatch(line, labelMap, tryCatches)) continue;

            AbstractInsnNode insn = parseInstruction(line, labelMap, i);
            if (insn != null) {
                LabelNode lbl = getOrCreate(labelMap, "L" + i);
                insns.add(lbl);
                insns.add(insn);
            }
        }
        return new ParsedMethod(insns, tryCatches);
    }

    // ── preprocessing ──────────────────────────────────────────────────

    private static List<String> preprocess(String text) {
        List<String> result = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("#") || line.startsWith("//")) continue;
            String u = line.toUpperCase(Locale.ROOT);
            if (u.startsWith("MAXSTACK") || u.startsWith("MAXLOCALS")
                    || u.startsWith("LOCALVARIABLE") || u.startsWith("FRAME")
                    || u.startsWith("F_SAME") || u.startsWith("F_APPEND")
                    || u.startsWith("F_CHOP") || u.startsWith("F_FULL")
                    || u.startsWith("F_NEW") || u.equals("//"))
                continue;
            if (line.matches("^\\s*L\\d+\\s*$")) continue; // bare label
            result.add(line);
        }
        return result;
    }

    // ── label / try-catch ──────────────────────────────────────────────

    private static boolean tryHandleLabel(String line, Map<String, LabelNode> map) {
        if (line.toUpperCase(Locale.ROOT).startsWith("LABEL ")) {
            getOrCreate(map, line.substring(6).strip());
            return true;
        }
        return false;
    }

    private static LabelNode getOrCreate(Map<String, LabelNode> map, String name) {
        return map.computeIfAbsent(name, k -> new LabelNode());
    }

    private static boolean tryHandleTryCatch(String line, Map<String, LabelNode> labels,
                                             List<TryCatchBlockNode> out) {
        String u = line.toUpperCase(Locale.ROOT);
        if (!u.startsWith("TRYCATCHBLOCK") && !u.startsWith("TRYCATCH ")) return false;
        String[] parts = line.split("\\s+");
        if (parts.length < 4) return true;
        LabelNode start = getOrCreate(labels, parts[1]);
        LabelNode end = getOrCreate(labels, parts[2]);
        LabelNode hnd = getOrCreate(labels, parts[3]);
        String type = parts.length >= 5 ? parts[4] : null;
        out.add(new TryCatchBlockNode(start, end, hnd, type));
        return true;
    }

    // ── instruction parser ─────────────────────────────────────────────

    private static AbstractInsnNode parseInstruction(String line,
                                                     Map<String, LabelNode> labels, int idx) {
        // LINE N or LINENUMBER N
        String ul = line.toUpperCase(Locale.ROOT);
        if (ul.startsWith("LINENUMBER ") || ul.startsWith("LINE ")) {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                try {
                    return new LineNumberNode(Integer.parseInt(parts[1]),
                            getOrCreate(labels, "L" + idx));
                } catch (NumberFormatException ignored) {
                }
            }
            return null;
        }

        List<String> tokens = tokenize(line);
        if (tokens.isEmpty()) return null;

        String opName = tokens.get(0).toUpperCase(Locale.ROOT);
        Integer op = NAME_TO_OPCODE.get(opName);
        if (op == null) return null; // unknown opcode → skip

        List<String> args = tokens.subList(1, tokens.size());
        LabelNode myLabel = getOrCreate(labels, "L" + idx);

        try {
            // no-arg
            if (!VAR_OPS.contains(op) && !INT_OPS.contains(op) && !TYPE_OPS.contains(op)
                    && !FIELD_OPS.contains(op) && !METHOD_OPS.contains(op)
                    && !JUMP_OPS.contains(op) && op != Opcodes.LDC
                    && op != Opcodes.IINC && op != Opcodes.MULTIANEWARRAY) {
                return new InsnNode(op);
            }
            // var
            if (VAR_OPS.contains(op)) {
                return new VarInsnNode(op, Integer.parseInt(args.get(0)));
            }
            // int
            if (INT_OPS.contains(op)) {
                return new IntInsnNode(op, Integer.parseInt(args.get(0)));
            }
            // type
            if (TYPE_OPS.contains(op)) {
                return new TypeInsnNode(op, args.get(0));
            }
            // field: GETFIELD owner/Name.fieldName : desc
            if (FIELD_OPS.contains(op)) {
                return parseField(op, args);
            }
            // method: INVOKEVIRTUAL owner/Name.methodName desc
            if (METHOD_OPS.contains(op)) {
                return parseMethod(op, args);
            }
            // jump
            if (JUMP_OPS.contains(op)) {
                LabelNode target = getOrCreate(labels, args.get(0));
                return new JumpInsnNode(op, target);
            }
            // ldc
            if (op == Opcodes.LDC) {
                return parseLdc(args);
            }
            // iinc
            if (op == Opcodes.IINC) {
                return new IincInsnNode(Integer.parseInt(args.get(0)), Integer.parseInt(args.get(1)));
            }
            // multianewarray
            if (op == Opcodes.MULTIANEWARRAY) {
                return new MultiANewArrayInsnNode(args.get(0), Integer.parseInt(args.get(1)));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse instruction: " + line + " — " + e.getMessage(), e);
        }
        return new InsnNode(op);
    }

    // ── field / method / ldc parsers ───────────────────────────────────

    private static FieldInsnNode parseField(int op, List<String> args) {
        // args[0] is "owner/Name.fieldName" (possibly with : and desc)
        // Build full remaining text, handling both "owner.name : desc" and single-token forms
        String joined = String.join(" ", args);
        // Split at " : "
        String[] parts = joined.split(" : ", 2);
        String ownerName = parts[0].strip();
        String desc = parts.length > 1 ? parts[1].strip() : "";

        // Split ownerName at last '.'
        int lastDot = ownerName.lastIndexOf('.');
        if (lastDot < 0) throw new IllegalArgumentException("invalid field ref: " + joined);
        String owner = ownerName.substring(0, lastDot);
        String name = ownerName.substring(lastDot + 1);

        if (desc.isEmpty()) throw new IllegalArgumentException("missing field descriptor in: " + joined);
        return new FieldInsnNode(op, owner, name, desc);
    }

    private static MethodInsnNode parseMethod(int op, List<String> args) {
        // Method ref: INVOKEVIRTUAL owner/Name.method desc
        // The owner.name is one token, desc is another (or owner.name desc are three tokens)
        String ownerName, desc;
        if (args.size() >= 2) {
            // Last token starts with '(' → it's the descriptor
            String last = args.get(args.size() - 1);
            if (last.startsWith("(")) {
                desc = last;
                ownerName = String.join(" ", args.subList(0, args.size() - 1));
            } else {
                // Fallback: first N-1 tokens are owner.name, last is desc
                desc = last;
                ownerName = String.join(" ", args.subList(0, args.size() - 1));
            }
        } else if (args.size() == 1) {
            // Single token: "owner.name" — no descriptor
            ownerName = args.get(0);
            desc = "";
        } else {
            throw new IllegalArgumentException("invalid method ref: " + String.join(" ", args));
        }

        int lastDot = ownerName.lastIndexOf('.');
        if (lastDot < 0) throw new IllegalArgumentException("invalid method ref: " + ownerName);
        String owner = ownerName.substring(0, lastDot);
        String name = ownerName.substring(lastDot + 1);

        boolean itf = op == Opcodes.INVOKEINTERFACE;
        return new MethodInsnNode(op, owner, name, desc, itf);
    }

    private static LdcInsnNode parseLdc(List<String> args) {
        String val = String.join(" ", args);
        // Try as int
        try {
            return new LdcInsnNode(Integer.parseInt(val));
        } catch (NumberFormatException ignored) {
        }
        // Try as long
        try {
            return new LdcInsnNode(Long.parseLong(val.replaceAll("L$", "")));
        } catch (NumberFormatException ignored) {
        }
        // Try as float
        try {
            return new LdcInsnNode(Float.parseFloat(val.replaceAll("F$", "")));
        } catch (NumberFormatException ignored) {
        }
        // Try as double
        try {
            return new LdcInsnNode(Double.parseDouble(val.replaceAll("D$", "")));
        } catch (NumberFormatException ignored) {
        }
        // String
        return new LdcInsnNode(val);
    }

    // ── tokenizer ──────────────────────────────────────────────────────

    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    buf.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (buf.length() > 0) {
                    tokens.add(buf.toString());
                    buf.setLength(0);
                }
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) tokens.add(buf.toString());
        return tokens;
    }
}
