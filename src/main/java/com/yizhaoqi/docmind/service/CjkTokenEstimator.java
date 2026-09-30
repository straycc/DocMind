package com.yizhaoqi.docmind.service;

/**
 * 稳定、可复现的检索 token 估算器，不等同于 Embedding 模型的真实分词器。
 */
public final class CjkTokenEstimator {

    private CjkTokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int tokens = 0;
        int latinRun = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isLatinOrDigit(codePoint)) {
                latinRun++;
                continue;
            }
            tokens += (latinRun + 3) / 4;
            latinRun = 0;
            if (!Character.isWhitespace(codePoint)) {
                tokens++;
            }
        }
        return tokens + (latinRun + 3) / 4;
    }

    private static boolean isLatinOrDigit(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9');
    }
}
