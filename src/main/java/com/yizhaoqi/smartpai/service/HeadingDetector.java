package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.HeadingConfidence;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PDF、DOCX 等纯文本行共用的保守标题兜底识别器。 */
public final class HeadingDetector {
    private static final Pattern CHINESE_NUMBERED_HEADING = Pattern.compile(
            "^第[一二三四五六七八九十百千万零〇0-9]+([章节条])\\s*(.{1,100})$");
    private static final Pattern CHINESE_CHAPTER = Pattern.compile("^[一二三四五六七八九十]+、\\s*(.{1,100})$");
    private static final Pattern CHINESE_SUBHEADING = Pattern.compile("^（[一二三四五六七八九十]+）\\s*(.{1,100})$");
    private static final Pattern NUMERIC_HEADING = Pattern.compile(
            "^(\\d+)(?:[.．](\\d+))?([.．、])?\\s+(\\S.{0,100})$");

    private HeadingDetector() {
    }

    /**
     * “章/节”可以作为 Section 边界；“条”保留为低置信的正文内标题，避免法规或制度按条过碎。
     */
    public static Optional<DetectedHeading> detect(String rawText) {
        String text = TextCleaner.clean(rawText);
        if (text.length() < 2 || text.length() > 120 || text.matches(".*[。！？；;!?]$")) {
            return Optional.empty();
        }
        Matcher chineseNumbered = CHINESE_NUMBERED_HEADING.matcher(text);
        if (chineseNumbered.matches()) {
            return switch (chineseNumbered.group(1)) {
                case "章" -> Optional.of(new DetectedHeading(1, HeadingConfidence.MEDIUM));
                case "节" -> Optional.of(new DetectedHeading(2, HeadingConfidence.MEDIUM));
                case "条" -> Optional.of(new DetectedHeading(3, HeadingConfidence.LOW));
                default -> Optional.empty();
            };
        }
        if (CHINESE_CHAPTER.matcher(text).matches()) {
            return Optional.of(new DetectedHeading(1, HeadingConfidence.MEDIUM));
        }
        if (CHINESE_SUBHEADING.matcher(text).matches()) {
            return Optional.of(new DetectedHeading(2, HeadingConfidence.MEDIUM));
        }
        Matcher numeric = NUMERIC_HEADING.matcher(text);
        if (numeric.matches() && (numeric.group(2) != null || numeric.group(3) != null)
                && !Character.isDigit(numeric.group(4).codePointAt(0))) {
            return Optional.of(new DetectedHeading(numeric.group(2) == null ? 1 : 2, HeadingConfidence.MEDIUM));
        }
        return Optional.empty();
    }

    public record DetectedHeading(int level, HeadingConfidence confidence) {
    }
}
