package com.yizhaoqi.docmind.service;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 文档清洗只处理确定不会改变语义的字符问题。 */
public final class TextCleaner {

    private TextCleaner() {
    }

    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return normalized;
    }

    /** 仅移除跨页重复且占比很小的高置信页眉/页脚。 */
    public static List<String> removeRepeatedPageChrome(List<String> pages) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (String page : pages) {
            String[] lines = clean(page).split("\\n");
            if (lines.length == 0) {
                continue;
            }
            addCandidate(occurrences, lines[0]);
            if (lines.length > 1) {
                addCandidate(occurrences, lines[lines.length - 1]);
            }
        }
        int threshold = Math.max(3, (int) Math.ceil(pages.size() * 0.6));
        return pages.stream().map(page -> {
            String[] lines = clean(page).split("\\n");
            return java.util.Arrays.stream(lines)
                    .filter(line -> occurrences.getOrDefault(clean(line), 0) < threshold || line.length() > 120)
                    .collect(Collectors.joining("\n"));
        }).toList();
    }

    private static void addCandidate(Map<String, Integer> occurrences, String line) {
        String candidate = clean(line);
        if (!candidate.isEmpty() && candidate.length() <= 120) {
            occurrences.merge(candidate, 1, Integer::sum);
        }
    }
}
