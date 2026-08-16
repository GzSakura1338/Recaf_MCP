package com.zinja.recafmcp.routes;

import java.util.Arrays;
import java.util.List;

final class MethodSourceSupport {
    private MethodSourceSupport() {
    }

    static ExtractedMethod extract(String text, String internalClassName, String methodName) {
        List<String> lines = Arrays.asList(text.split("\\R", -1));
        String needle = methodNeedle(simpleClassName(internalClassName), methodName);

        for (int i = 0; i < lines.size(); i++) {
            if (!looksLikeDeclaration(lines.get(i), needle, methodName)) continue;
            int start = annotationStart(lines, i);
            int end = findMethodEnd(lines, i, methodName);
            if (end < i) continue;
            return new ExtractedMethod(String.join(System.lineSeparator(), lines.subList(start, end + 1)), start + 1, end + 1);
        }
        return null;
    }

    private static boolean looksLikeDeclaration(String line, String needle, String methodName) {
        String trimmed = line.trim();
        if (methodName.equals("<clinit>")) return trimmed.startsWith("static");
        if (!trimmed.contains(needle)) return false;
        if (trimmed.startsWith("return ") || trimmed.startsWith("throw ")) return false;
        return trimmed.contains("public ")
                || trimmed.contains("private ")
                || trimmed.contains("protected ")
                || trimmed.contains("static ")
                || trimmed.contains("final ")
                || trimmed.contains("abstract ")
                || trimmed.contains("native ")
                || trimmed.contains("synchronized ")
                || trimmed.startsWith(simpleToken(needle))
                || trimmed.startsWith("<");
    }

    private static int annotationStart(List<String> lines, int index) {
        int start = index;
        while (start > 0 && lines.get(start - 1).trim().startsWith("@")) {
            start--;
        }
        return start;
    }

    private static int findMethodEnd(List<String> lines, int start, String methodName) {
        boolean inString = false;
        boolean escape = false;
        char quote = 0;
        int depth = 0;
        boolean seenBody = methodName.equals("<clinit>");

        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (inString) {
                    if (escape) {
                        escape = false;
                    } else if (c == '\\') {
                        escape = true;
                    } else if (c == quote) {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"' || c == '\'') {
                    inString = true;
                    quote = c;
                    continue;
                }
                if (!seenBody && c == ';') return i;
                if (c == '{') {
                    depth++;
                    seenBody = true;
                } else if (c == '}') {
                    depth--;
                    if (seenBody && depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static String methodNeedle(String className, String methodName) {
        if (methodName.equals("<init>")) return className + "(";
        if (methodName.equals("<clinit>")) return "static";
        return methodName + "(";
    }

    private static String simpleToken(String text) {
        return text.endsWith("(") ? text.substring(0, text.length() - 1) : text;
    }

    private static String simpleClassName(String internalName) {
        String name = internalName.substring(internalName.lastIndexOf('/') + 1);
        int inner = name.lastIndexOf('$');
        return inner >= 0 ? name.substring(inner + 1) : name;
    }

    record ExtractedMethod(String source, int lineStart, int lineEnd) {
    }
}
