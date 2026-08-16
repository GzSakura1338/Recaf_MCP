package com.zinja.recafmcp.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.util.*;

final class BytecodeEditSupport {

    private BytecodeEditSupport() {
    }

    // ── public helpers used by BytecodeEditRoutes ──────────────────────

    static boolean isJumpOpcode(String name) {
        Integer op = NAME_TO_OPCODE.get(name.toUpperCase(Locale.ROOT));
        return op != null && JUMP_OPCODES.contains(op);
    }

    static AbstractInsnNode buildSingleInstruction(String name, JsonArray args) {
        LabelNode[] labels = new LabelNode[1];
        labels[0] = new LabelNode();
        return buildInstruction(name, args, labels, 0);
    }

    // ── opcode name ↔ int maps ──────────────────────────────────────────

    private static final Map<String, Integer> NAME_TO_OPCODE = new HashMap<>();
    private static final Map<Integer, String> OPCODE_TO_NAME = new HashMap<>();

    static {
        // no-arg
        reg("NOP", Opcodes.NOP);
        reg("ACONST_NULL", Opcodes.ACONST_NULL);
        reg("ICONST_M1", Opcodes.ICONST_M1);
        reg("ICONST_0", Opcodes.ICONST_0);
        reg("ICONST_1", Opcodes.ICONST_1);
        reg("ICONST_2", Opcodes.ICONST_2);
        reg("ICONST_3", Opcodes.ICONST_3);
        reg("ICONST_4", Opcodes.ICONST_4);
        reg("ICONST_5", Opcodes.ICONST_5);
        reg("LCONST_0", Opcodes.LCONST_0);
        reg("LCONST_1", Opcodes.LCONST_1);
        reg("FCONST_0", Opcodes.FCONST_0);
        reg("FCONST_1", Opcodes.FCONST_1);
        reg("FCONST_2", Opcodes.FCONST_2);
        reg("DCONST_0", Opcodes.DCONST_0);
        reg("DCONST_1", Opcodes.DCONST_1);
        reg("IALOAD", Opcodes.IALOAD);
        reg("LALOAD", Opcodes.LALOAD);
        reg("FALOAD", Opcodes.FALOAD);
        reg("DALOAD", Opcodes.DALOAD);
        reg("AALOAD", Opcodes.AALOAD);
        reg("BALOAD", Opcodes.BALOAD);
        reg("CALOAD", Opcodes.CALOAD);
        reg("SALOAD", Opcodes.SALOAD);
        reg("IASTORE", Opcodes.IASTORE);
        reg("LASTORE", Opcodes.LASTORE);
        reg("FASTORE", Opcodes.FASTORE);
        reg("DASTORE", Opcodes.DASTORE);
        reg("AASTORE", Opcodes.AASTORE);
        reg("BASTORE", Opcodes.BASTORE);
        reg("CASTORE", Opcodes.CASTORE);
        reg("SASTORE", Opcodes.SASTORE);
        reg("POP", Opcodes.POP);
        reg("POP2", Opcodes.POP2);
        reg("DUP", Opcodes.DUP);
        reg("DUP_X1", Opcodes.DUP_X1);
        reg("DUP_X2", Opcodes.DUP_X2);
        reg("DUP2", Opcodes.DUP2);
        reg("DUP2_X1", Opcodes.DUP2_X1);
        reg("DUP2_X2", Opcodes.DUP2_X2);
        reg("SWAP", Opcodes.SWAP);
        reg("IADD", Opcodes.IADD);
        reg("LADD", Opcodes.LADD);
        reg("FADD", Opcodes.FADD);
        reg("DADD", Opcodes.DADD);
        reg("ISUB", Opcodes.ISUB);
        reg("LSUB", Opcodes.LSUB);
        reg("FSUB", Opcodes.FSUB);
        reg("DSUB", Opcodes.DSUB);
        reg("IMUL", Opcodes.IMUL);
        reg("LMUL", Opcodes.LMUL);
        reg("FMUL", Opcodes.FMUL);
        reg("DMUL", Opcodes.DMUL);
        reg("IDIV", Opcodes.IDIV);
        reg("LDIV", Opcodes.LDIV);
        reg("FDIV", Opcodes.FDIV);
        reg("DDIV", Opcodes.DDIV);
        reg("IREM", Opcodes.IREM);
        reg("LREM", Opcodes.LREM);
        reg("FREM", Opcodes.FREM);
        reg("DREM", Opcodes.DREM);
        reg("INEG", Opcodes.INEG);
        reg("LNEG", Opcodes.LNEG);
        reg("FNEG", Opcodes.FNEG);
        reg("DNEG", Opcodes.DNEG);
        reg("ISHL", Opcodes.ISHL);
        reg("LSHL", Opcodes.LSHL);
        reg("ISHR", Opcodes.ISHR);
        reg("LSHR", Opcodes.LSHR);
        reg("IUSHR", Opcodes.IUSHR);
        reg("LUSHR", Opcodes.LUSHR);
        reg("IAND", Opcodes.IAND);
        reg("LAND", Opcodes.LAND);
        reg("IOR", Opcodes.IOR);
        reg("LOR", Opcodes.LOR);
        reg("IXOR", Opcodes.IXOR);
        reg("LXOR", Opcodes.LXOR);
        reg("I2L", Opcodes.I2L);
        reg("I2F", Opcodes.I2F);
        reg("I2D", Opcodes.I2D);
        reg("L2I", Opcodes.L2I);
        reg("L2F", Opcodes.L2F);
        reg("L2D", Opcodes.L2D);
        reg("F2I", Opcodes.F2I);
        reg("F2L", Opcodes.F2L);
        reg("F2D", Opcodes.F2D);
        reg("D2I", Opcodes.D2I);
        reg("D2L", Opcodes.D2L);
        reg("D2F", Opcodes.D2F);
        reg("I2B", Opcodes.I2B);
        reg("I2C", Opcodes.I2C);
        reg("I2S", Opcodes.I2S);
        reg("LCMP", Opcodes.LCMP);
        reg("FCMPL", Opcodes.FCMPL);
        reg("FCMPG", Opcodes.FCMPG);
        reg("DCMPL", Opcodes.DCMPL);
        reg("DCMPG", Opcodes.DCMPG);
        reg("IRETURN", Opcodes.IRETURN);
        reg("LRETURN", Opcodes.LRETURN);
        reg("FRETURN", Opcodes.FRETURN);
        reg("DRETURN", Opcodes.DRETURN);
        reg("ARETURN", Opcodes.ARETURN);
        reg("RETURN", Opcodes.RETURN);
        reg("ARRAYLENGTH", Opcodes.ARRAYLENGTH);
        reg("ATHROW", Opcodes.ATHROW);
        reg("MONITORENTER", Opcodes.MONITORENTER);
        reg("MONITOREXIT", Opcodes.MONITOREXIT);

        // var
        reg("ILOAD", Opcodes.ILOAD);
        reg("LLOAD", Opcodes.LLOAD);
        reg("FLOAD", Opcodes.FLOAD);
        reg("DLOAD", Opcodes.DLOAD);
        reg("ALOAD", Opcodes.ALOAD);
        reg("ISTORE", Opcodes.ISTORE);
        reg("LSTORE", Opcodes.LSTORE);
        reg("FSTORE", Opcodes.FSTORE);
        reg("DSTORE", Opcodes.DSTORE);
        reg("ASTORE", Opcodes.ASTORE);
        reg("RET", Opcodes.RET);

        // int
        reg("BIPUSH", Opcodes.BIPUSH);
        reg("SIPUSH", Opcodes.SIPUSH);
        reg("NEWARRAY", Opcodes.NEWARRAY);

        // type
        reg("NEW", Opcodes.NEW);
        reg("ANEWARRAY", Opcodes.ANEWARRAY);
        reg("CHECKCAST", Opcodes.CHECKCAST);
        reg("INSTANCEOF", Opcodes.INSTANCEOF);

        // field
        reg("GETFIELD", Opcodes.GETFIELD);
        reg("PUTFIELD", Opcodes.PUTFIELD);
        reg("GETSTATIC", Opcodes.GETSTATIC);
        reg("PUTSTATIC", Opcodes.PUTSTATIC);

        // method
        reg("INVOKEVIRTUAL", Opcodes.INVOKEVIRTUAL);
        reg("INVOKESPECIAL", Opcodes.INVOKESPECIAL);
        reg("INVOKESTATIC", Opcodes.INVOKESTATIC);
        reg("INVOKEINTERFACE", Opcodes.INVOKEINTERFACE);

        // jump
        reg("IFEQ", Opcodes.IFEQ);
        reg("IFNE", Opcodes.IFNE);
        reg("IFLT", Opcodes.IFLT);
        reg("IFGE", Opcodes.IFGE);
        reg("IFGT", Opcodes.IFGT);
        reg("IFLE", Opcodes.IFLE);
        reg("IF_ICMPEQ", Opcodes.IF_ICMPEQ);
        reg("IF_ICMPNE", Opcodes.IF_ICMPNE);
        reg("IF_ICMPLT", Opcodes.IF_ICMPLT);
        reg("IF_ICMPGE", Opcodes.IF_ICMPGE);
        reg("IF_ICMPGT", Opcodes.IF_ICMPGT);
        reg("IF_ICMPLE", Opcodes.IF_ICMPLE);
        reg("IF_ACMPEQ", Opcodes.IF_ACMPEQ);
        reg("IF_ACMPNE", Opcodes.IF_ACMPNE);
        reg("GOTO", Opcodes.GOTO);
        reg("JSR", Opcodes.JSR);
        reg("IFNULL", Opcodes.IFNULL);
        reg("IFNONNULL", Opcodes.IFNONNULL);

        // ldc
        reg("LDC", Opcodes.LDC);

        // iinc
        reg("IINC", Opcodes.IINC);

        // multianewarray
        reg("MULTIANEWARRAY", Opcodes.MULTIANEWARRAY);

        // invokedynamic
        reg("INVOKEDYNAMIC", Opcodes.INVOKEDYNAMIC);

        // tableswitch / lookupswitch
        reg("TABLESWITCH", Opcodes.TABLESWITCH);
        reg("LOOKUPSWITCH", Opcodes.LOOKUPSWITCH);
    }

    private static void reg(String name, int opcode) {
        NAME_TO_OPCODE.put(name, opcode);
        OPCODE_TO_NAME.put(opcode, name);
    }

    // ── instruction set categories ──────────────────────────────────────

    private static final Set<Integer> VAR_OPCODES = Set.of(
            Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD,
            Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE,
            Opcodes.RET);

    private static final Set<Integer> INT_OPCODES = Set.of(
            Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.NEWARRAY);

    private static final Set<Integer> TYPE_OPCODES = Set.of(
            Opcodes.NEW, Opcodes.ANEWARRAY, Opcodes.CHECKCAST, Opcodes.INSTANCEOF);

    private static final Set<Integer> FIELD_OPCODES = Set.of(
            Opcodes.GETFIELD, Opcodes.PUTFIELD, Opcodes.GETSTATIC, Opcodes.PUTSTATIC);

    private static final Set<Integer> METHOD_OPCODES = Set.of(
            Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE);

    private static final Set<Integer> JUMP_OPCODES = Set.of(
            Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
            Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
            Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
            Opcodes.GOTO, Opcodes.JSR, Opcodes.IFNULL, Opcodes.IFNONNULL);

    // ── serialization: MethodNode → JsonArray ───────────────────────────

    static JsonArray instructionsToJson(MethodNode method) {
        InsnList insns = method.instructions;
        if (insns == null || insns.size() == 0) return new JsonArray();

        // assign every AbstractInsnNode an index
        Map<AbstractInsnNode, Integer> pos = new HashMap<>();
        int idx = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            pos.put(insn, idx++);
        }

        JsonArray arr = new JsonArray();
        idx = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            JsonObject obj = serializeInstruction(insn, pos);
            obj.addProperty("index", idx++);
            arr.add(obj);
        }
        return arr;
    }

    private static JsonObject serializeInstruction(AbstractInsnNode insn, Map<AbstractInsnNode, Integer> pos) {
        JsonObject obj = new JsonObject();

        if (insn instanceof LabelNode) {
            obj.addProperty("type", "label");
            return obj;
        }
        if (insn instanceof FrameNode frame) {
            obj.addProperty("type", "frame");
            obj.addProperty("frame_type", frame.type);
            return obj;
        }
        if (insn instanceof LineNumberNode line) {
            obj.addProperty("type", "line");
            obj.addProperty("opcode", "LINENUMBER");
            JsonArray args = new JsonArray();
            args.add(line.line);
            obj.add("args", args);
            return obj;
        }

        int opcode = insn.getOpcode();
        String name = OPCODE_TO_NAME.getOrDefault(opcode, "UNKNOWN_" + opcode);
        obj.addProperty("opcode", name);

        JsonArray args = new JsonArray();

        if (VAR_OPCODES.contains(opcode)) {
            args.add(((VarInsnNode) insn).var);
        } else if (INT_OPCODES.contains(opcode)) {
            args.add(((IntInsnNode) insn).operand);
        } else if (TYPE_OPCODES.contains(opcode)) {
            args.add(RouteSupport.external(((TypeInsnNode) insn).desc));
        } else if (FIELD_OPCODES.contains(opcode)) {
            FieldInsnNode f = (FieldInsnNode) insn;
            args.add(RouteSupport.external(f.owner));
            args.add(f.name);
            args.add(f.desc);
        } else if (METHOD_OPCODES.contains(opcode)) {
            MethodInsnNode m = (MethodInsnNode) insn;
            args.add(RouteSupport.external(m.owner));
            args.add(m.name);
            args.add(m.desc);
        } else if (insn instanceof InvokeDynamicInsnNode indy) {
            // INVOKEDYNAMIC
            args.add(indy.name);
            args.add(indy.desc);
            args.add(RouteSupport.external(indy.bsm.getOwner()));
            args.add(indy.bsm.getName());
            args.add(indy.bsm.getDesc());
            for (Object bsmArg : indy.bsmArgs) {
                if (bsmArg instanceof String s) args.add(s);
                else if (bsmArg instanceof Number n) args.add(n);
                else args.add(String.valueOf(bsmArg));
            }
        } else if (JUMP_OPCODES.contains(opcode)) {
            LabelNode target = ((JumpInsnNode) insn).label;
            args.add(pos.getOrDefault(target, -1));
        } else if (opcode == Opcodes.LDC) {
            Object cst = ((LdcInsnNode) insn).cst;
            if (cst instanceof String s) args.add(s);
            else if (cst instanceof Number n) args.add(n);
            else args.add(String.valueOf(cst));
        } else if (opcode == Opcodes.IINC) {
            IincInsnNode iinc = (IincInsnNode) insn;
            args.add(iinc.var);
            args.add(iinc.incr);
        } else if (opcode == Opcodes.MULTIANEWARRAY) {
            MultiANewArrayInsnNode ma = (MultiANewArrayInsnNode) insn;
            args.add(ma.desc);
            args.add(ma.dims);
        } else if (opcode == Opcodes.TABLESWITCH) {
            TableSwitchInsnNode ts = (TableSwitchInsnNode) insn;
            args.add(ts.min);
            args.add(ts.max);
            args.add(pos.getOrDefault(ts.dflt, -1));
            for (LabelNode l : ts.labels) args.add(pos.getOrDefault(l, -1));
        } else if (opcode == Opcodes.LOOKUPSWITCH) {
            LookupSwitchInsnNode ls = (LookupSwitchInsnNode) insn;
            args.add(pos.getOrDefault(ls.dflt, -1));
            for (int i = 0; i < ls.keys.size(); i++) {
                args.add(ls.keys.get(i));
                args.add(pos.getOrDefault(ls.labels.get(i), -1));
            }
        }

        if (args.size() > 0) obj.add("args", args);
        return obj;
    }

    // ── deserialization: JsonArray → InsnList ───────────────────────────

    static InsnList jsonToInsnList(JsonArray instructions) {
        InsnList list = new InsnList();
        int count = instructions.size();

        // pre-create labels for every position (used as jump targets)
        LabelNode[] labels = new LabelNode[count];
        for (int i = 0; i < count; i++) labels[i] = new LabelNode();

        for (int i = 0; i < count; i++) {
            JsonObject obj = instructions.get(i).getAsJsonObject();
            list.add(labels[i]);
            String type = obj.has("type") ? obj.get("type").getAsString() : null;

            if ("line".equals(type)) {
                int line = obj.has("args") ? obj.getAsJsonArray("args").get(0).getAsInt() : -1;
                if (line >= 0) list.add(new LineNumberNode(line, labels[i]));
            } else if (!"label".equals(type) && !"frame".equals(type)) {
                list.add(buildInstruction(obj, labels, i));
            }
        }
        return list;
    }

    private static AbstractInsnNode buildInstruction(JsonObject obj, LabelNode[] labels, int currentIndex) {
        String name = obj.get("opcode").getAsString().toUpperCase(Locale.ROOT);
        JsonArray args = obj.has("args") ? obj.getAsJsonArray("args") : new JsonArray();
        return buildInstruction(name, args, labels, currentIndex);
    }

    private static AbstractInsnNode buildInstruction(String name, JsonArray args, LabelNode[] labels, int currentIndex) {
        Integer opcode = NAME_TO_OPCODE.get(name);
        if (opcode == null) throw new IllegalArgumentException("unknown opcode: " + name);

        if (VAR_OPCODES.contains(opcode)) {
            return new VarInsnNode(opcode, args.get(0).getAsInt());
        }
        if (INT_OPCODES.contains(opcode)) {
            return new IntInsnNode(opcode, args.get(0).getAsInt());
        }
        if (TYPE_OPCODES.contains(opcode)) {
            return new TypeInsnNode(opcode, RouteSupport.internal(args.get(0).getAsString()));
        }
        if (FIELD_OPCODES.contains(opcode)) {
            return new FieldInsnNode(opcode,
                    RouteSupport.internal(args.get(0).getAsString()),
                    args.get(1).getAsString(),
                    args.get(2).getAsString());
        }
        if (METHOD_OPCODES.contains(opcode)) {
            boolean isInterface = opcode == Opcodes.INVOKEINTERFACE;
            return new MethodInsnNode(opcode,
                    RouteSupport.internal(args.get(0).getAsString()),
                    args.get(1).getAsString(),
                    args.get(2).getAsString(),
                    isInterface);
        }
        if (opcode == Opcodes.INVOKEDYNAMIC) {
            String indyName = args.get(0).getAsString();
            String indyDesc = args.get(1).getAsString();
            String bsmOwner = RouteSupport.internal(args.get(2).getAsString());
            String bsmName = args.get(3).getAsString();
            String bsmDesc = args.get(4).getAsString();
            Object[] bsmArgs = new Object[args.size() - 5];
            for (int j = 5; j < args.size(); j++) {
                JsonElement el = args.get(j);
                if (el.getAsJsonPrimitive().isNumber()) bsmArgs[j - 5] = el.getAsInt();
                else bsmArgs[j - 5] = el.getAsString();
            }
            return new InvokeDynamicInsnNode(indyName, indyDesc,
                    new org.objectweb.asm.Handle(
                            org.objectweb.asm.Opcodes.H_INVOKESTATIC,
                            bsmOwner, bsmName, bsmDesc, false),
                    bsmArgs);
        }
        if (JUMP_OPCODES.contains(opcode)) {
            int targetIdx = args.get(0).getAsInt();
            if (targetIdx < 0 || targetIdx >= labels.length)
                throw new IllegalArgumentException("jump target index " + targetIdx + " out of range [0, " + labels.length + ")");
            return new JumpInsnNode(opcode, labels[targetIdx]);
        }
        if (opcode == Opcodes.LDC) {
            JsonPrimitive p = args.get(0).getAsJsonPrimitive();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE)
                    return new LdcInsnNode((int) d);
                return new LdcInsnNode((float) d);
            }
            return new LdcInsnNode(p.getAsString());
        }
        if (opcode == Opcodes.IINC) {
            return new IincInsnNode(args.get(0).getAsInt(), args.get(1).getAsInt());
        }
        if (opcode == Opcodes.MULTIANEWARRAY) {
            return new MultiANewArrayInsnNode(args.get(0).getAsString(), args.get(1).getAsInt());
        }
        if (opcode == Opcodes.TABLESWITCH) {
            int min = args.get(0).getAsInt();
            int max = args.get(1).getAsInt();
            int dfltIdx = args.get(2).getAsInt();
            LabelNode[] caseLabels = new LabelNode[args.size() - 3];
            for (int j = 3; j < args.size(); j++) {
                int idx = args.get(j).getAsInt();
                if (idx < 0 || idx >= labels.length)
                    throw new IllegalArgumentException("tableswitch target index out of range: " + idx);
                caseLabels[j - 3] = labels[idx];
            }
            return new TableSwitchInsnNode(min, max, labels[dfltIdx], caseLabels);
        }
        if (opcode == Opcodes.LOOKUPSWITCH) {
            int dfltIdx = args.get(0).getAsInt();
            List<Integer> keys = new ArrayList<>();
            List<LabelNode> caseLabels = new ArrayList<>();
            for (int j = 1; j < args.size(); j += 2) {
                keys.add(args.get(j).getAsInt());
                int idx = args.get(j + 1).getAsInt();
                if (idx < 0 || idx >= labels.length)
                    throw new IllegalArgumentException("lookupswitch target index out of range: " + idx);
                caseLabels.add(labels[idx]);
            }
            return new LookupSwitchInsnNode(labels[dfltIdx],
                    keys.stream().mapToInt(Integer::intValue).toArray(),
                    caseLabels.toArray(new LabelNode[0]));
        }

        // no-arg
        return new InsnNode(opcode);
    }

    // ── write-back: replace method body, update workspace bundle ────────

    static int replaceMethodBody(Workspace ws, RouteSupport.MethodTarget target, JsonArray instructions) {
        ClassNode classNode = readClassNode(target.classInfo());
        for (MethodNode method : classNode.methods) {
            if (!method.name.equals(target.name()) || !method.desc.equals(target.descriptor())) continue;
            method.instructions = jsonToInsnList(instructions);
            commitClassNode(ws, target.owner(), target.classInfo(), classNode);
            return 1;
        }
        return 0;
    }

    // ── JASM text assembly ──────────────────────────────────────────────

    static int assembleMethodBody(Workspace ws, RouteSupport.MethodTarget target, String jasmText) {
        JasmSupport.ParsedMethod parsed = JasmSupport.parse(jasmText);
        ClassNode classNode = readClassNode(target.classInfo());
        for (MethodNode method : classNode.methods) {
            if (!method.name.equals(target.name()) || !method.desc.equals(target.descriptor())) continue;
            method.instructions = parsed.instructions;
            if (!parsed.tryCatchBlocks.isEmpty()) {
                method.tryCatchBlocks.clear();
                method.tryCatchBlocks.addAll(parsed.tryCatchBlocks);
            }
            commitClassNode(ws, target.owner(), target.classInfo(), classNode);
            return 1;
        }
        return 0;
    }

    // ── access flag editing ─────────────────────────────────────────────

    static final Map<String, Integer> ACCESS_FLAGS = new LinkedHashMap<>();

    static {
        ACCESS_FLAGS.put("public", Opcodes.ACC_PUBLIC);
        ACCESS_FLAGS.put("private", Opcodes.ACC_PRIVATE);
        ACCESS_FLAGS.put("protected", Opcodes.ACC_PROTECTED);
        ACCESS_FLAGS.put("static", Opcodes.ACC_STATIC);
        ACCESS_FLAGS.put("final", Opcodes.ACC_FINAL);
        ACCESS_FLAGS.put("synchronized", Opcodes.ACC_SYNCHRONIZED);
        ACCESS_FLAGS.put("volatile", Opcodes.ACC_VOLATILE);
        ACCESS_FLAGS.put("transient", Opcodes.ACC_TRANSIENT);
        ACCESS_FLAGS.put("native", Opcodes.ACC_NATIVE);
        ACCESS_FLAGS.put("interface", Opcodes.ACC_INTERFACE);
        ACCESS_FLAGS.put("abstract", Opcodes.ACC_ABSTRACT);
        ACCESS_FLAGS.put("strictfp", Opcodes.ACC_STRICT);
        ACCESS_FLAGS.put("synthetic", Opcodes.ACC_SYNTHETIC);
        ACCESS_FLAGS.put("annotation", Opcodes.ACC_ANNOTATION);
        ACCESS_FLAGS.put("enum", Opcodes.ACC_ENUM);
        ACCESS_FLAGS.put("bridge", Opcodes.ACC_BRIDGE);
        ACCESS_FLAGS.put("varargs", Opcodes.ACC_VARARGS);
    }

    static int applyAccessFlags(int currentAccess, List<String> setFlags, List<String> clearFlags) {
        for (String f : setFlags) {
            Integer v = ACCESS_FLAGS.get(f.toLowerCase(Locale.ROOT));
            if (v != null) currentAccess |= v;
        }
        for (String f : clearFlags) {
            Integer v = ACCESS_FLAGS.get(f.toLowerCase(Locale.ROOT));
            if (v != null) currentAccess &= ~v;
        }
        return currentAccess;
    }

    static int editClassAccess(Workspace ws, String className,
                               List<String> setFlags, List<String> clearFlags) {
        JvmClassInfo cls = findClass(ws, className);
        if (cls == null) return 0;

        ClassNode classNode = readClassNode(cls);
        classNode.access = applyAccessFlags(classNode.access, setFlags, clearFlags);
        commitClassNode(ws, RouteSupport.internal(className), cls, classNode);
        return 1;
    }

    static int editMethodAccess(Workspace ws, RouteSupport.MethodTarget target,
                                List<String> setFlags, List<String> clearFlags) {
        ClassNode classNode = readClassNode(target.classInfo());
        for (MethodNode method : classNode.methods) {
            if (!method.name.equals(target.name()) || !method.desc.equals(target.descriptor())) continue;
            method.access = applyAccessFlags(method.access, setFlags, clearFlags);
            commitClassNode(ws, target.owner(), target.classInfo(), classNode);
            return 1;
        }
        return 0;
    }

    static int editFieldAccess(Workspace ws, String className, String fieldName,
                               String descriptor, List<String> setFlags, List<String> clearFlags) {
        JvmClassInfo cls = findClass(ws, className);
        if (cls == null) return 0;

        ClassNode classNode = readClassNode(cls);
        for (FieldNode field : classNode.fields) {
            boolean nameMatch = field.name.equals(fieldName);
            boolean descMatch = descriptor == null || descriptor.isBlank() || field.desc.equals(descriptor);
            if (!nameMatch || !descMatch) continue;
            field.access = applyAccessFlags(field.access, setFlags, clearFlags);
            commitClassNode(ws, RouteSupport.internal(className), cls, classNode);
            return 1;
        }
        return 0;
    }

    // ── replace class bytes ─────────────────────────────────────────────

    static int replaceClassBytes(Workspace ws, String className, byte[] bytes) {
        String internal = RouteSupport.internal(className);
        JvmClassBundle bundle = RouteSupport.primaryBundles(ws.getPrimaryResource())
                .filter(b -> b.containsKey(internal))
                .findFirst().orElse(null);
        if (bundle == null) return 0;

        JvmClassInfo original = (JvmClassInfo) ws.findJvmClass(internal).getValue();
        JvmClassInfo updated = original.toJvmClassBuilder().withBytecode(bytes).build();
        bundle.put(updated);
        return 1;
    }

    // ── add / remove methods ────────────────────────────────────────────

    static int addMethod(Workspace ws, String className, String methodName, String descriptor,
                         int access, JsonArray instructions) {
        JvmClassInfo cls = findClass(ws, className);
        if (cls == null) return 0;

        ClassNode classNode = readClassNode(cls);
        // Check for duplicate
        for (MethodNode m : classNode.methods) {
            if (m.name.equals(methodName) && m.desc.equals(descriptor)) return -1;
        }

        MethodNode method = new MethodNode(access, methodName, descriptor, null, null);
        if (instructions != null && !instructions.isEmpty()) {
            method.instructions = jsonToInsnList(instructions);
        }
        classNode.methods.add(method);
        commitClassNode(ws, RouteSupport.internal(className), cls, classNode);
        return 1;
    }

    static int removeMethod(Workspace ws, RouteSupport.MethodTarget target) {
        ClassNode classNode = readClassNode(target.classInfo());
        boolean removed = classNode.methods.removeIf(m ->
                m.name.equals(target.name()) && m.desc.equals(target.descriptor()));
        if (!removed) return 0;
        commitClassNode(ws, target.owner(), target.classInfo(), classNode);
        return 1;
    }

    // ── add / remove fields ─────────────────────────────────────────────

    static int addField(Workspace ws, String className, String fieldName, String descriptor,
                        int access, String signature, Object value) {
        JvmClassInfo cls = findClass(ws, className);
        if (cls == null) return 0;

        ClassNode classNode = readClassNode(cls);
        for (FieldNode f : classNode.fields) {
            if (f.name.equals(fieldName) && f.desc.equals(descriptor)) return -1;
        }

        FieldNode field = new FieldNode(access, fieldName, descriptor, signature, value);
        classNode.fields.add(field);
        commitClassNode(ws, RouteSupport.internal(className), cls, classNode);
        return 1;
    }

    static int removeField(Workspace ws, String className, String fieldName, String descriptor) {
        JvmClassInfo cls = findClass(ws, className);
        if (cls == null) return 0;

        ClassNode classNode = readClassNode(cls);
        boolean removed = classNode.fields.removeIf(f ->
                f.name.equals(fieldName) && (descriptor == null || descriptor.isBlank() || f.desc.equals(descriptor)));
        if (!removed) return 0;
        commitClassNode(ws, RouteSupport.internal(className), cls, classNode);
        return 1;
    }

    // ── try-catch blocks ────────────────────────────────────────────────

    static int setTryCatchBlocks(Workspace ws, RouteSupport.MethodTarget target,
                                 JsonArray tcArray) {
        ClassNode classNode = readClassNode(target.classInfo());
        for (MethodNode method : classNode.methods) {
            if (!method.name.equals(target.name()) || !method.desc.equals(target.descriptor())) continue;

            // build label map from existing instructions
            Map<String, LabelNode> labels = new LinkedHashMap<>();
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LabelNode ln) {
                    labels.putIfAbsent("L" + labels.size(), ln);
                }
            }

            List<TryCatchBlockNode> blocks = new ArrayList<>();
            for (int i = 0; i < tcArray.size(); i++) {
                JsonObject tc = tcArray.get(i).getAsJsonObject();
                String start = tc.get("start").getAsString();
                String end = tc.get("end").getAsString();
                String handler = tc.get("handler").getAsString();
                String type = tc.has("type") && !tc.get("type").isJsonNull() ? tc.get("type").getAsString() : null;
                LabelNode lStart = labels.computeIfAbsent(start, k -> new LabelNode());
                LabelNode lEnd = labels.computeIfAbsent(end, k -> new LabelNode());
                LabelNode lHnd = labels.computeIfAbsent(handler, k -> new LabelNode());
                blocks.add(new TryCatchBlockNode(lStart, lEnd, lHnd, type));
            }

            method.tryCatchBlocks.clear();
            method.tryCatchBlocks.addAll(blocks);
            commitClassNode(ws, target.owner(), target.classInfo(), classNode);
            return 1;
        }
        return 0;
    }

    // ── shared helpers ──────────────────────────────────────────────────

    static ClassNode readClassNode(JvmClassInfo cls) {
        ClassNode cn = new ClassNode();
        new ClassReader(cls.getBytecode()).accept(cn, 0);
        return cn;
    }

    static void commitClassNode(Workspace ws, String owner, JvmClassInfo original, ClassNode classNode) {
        JvmClassBundle bundle = RouteSupport.primaryBundles(ws.getPrimaryResource())
                .filter(b -> b.containsKey(owner))
                .findFirst().orElse(null);
        if (bundle == null) return;

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (RuntimeException e) {
                    return "java/lang/Object";
                }
            }
        };
        classNode.accept(writer);
        JvmClassInfo updated = original.toJvmClassBuilder().withBytecode(writer.toByteArray()).build();
        bundle.put(updated);
    }

    static JvmClassInfo findClass(Workspace ws, String className) {
        var node = ws.findJvmClass(RouteSupport.internal(className));
        return node != null ? (JvmClassInfo) node.getValue() : null;
    }

    static JvmClassBundle findBundle(Workspace ws, String internalName) {
        return RouteSupport.primaryBundles(ws.getPrimaryResource())
                .filter(b -> b.containsKey(internalName))
                .findFirst().orElse(null);
    }
}
