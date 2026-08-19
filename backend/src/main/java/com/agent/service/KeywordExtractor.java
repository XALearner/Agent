package com.agent.service;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.agent.exception.BizException;
import com.huaban.analysis.jieba.JiebaSegmenter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class KeywordExtractor {

    private static final int DEFAULT_TOP_N = 32;
    private static final Pattern CHINESE_CHAR = Pattern.compile("\\p{IsHan}");
    private static final Pattern ENGLISH_WORD = Pattern.compile("[a-zA-Z][a-zA-Z0-9_+#.\\-]*");
    private static final Pattern NUMBER = Pattern.compile("[0-9][0-9,.\\-]*");
    private static final Pattern PUNCTUATION = Pattern.compile("[~\\t @#%!<>,.?\":;'{}\\[\\]_=(|，。？》•●○↓《；‘’：“”【¥ 】…￥！、·（）×`&/「」\\\\]+");
    private static final Pattern QUESTION_WORDS_ZH = Pattern.compile("是*(什么样的|哪家|一下|那家|请问|啥样|咋样了|什么时候|何时|何地|何人|是否|是不是|多少|哪里|怎么|哪儿|怎么样|如何|哪些|是啥|啥是|啊|吗|呢|吧|咋|什么|有没有|呀|谁|哪位|哪个)是*");
    private static final Pattern QUESTION_WORDS_EN = Pattern.compile("(?i)(^| )(what|who|how|which|where|why)('re|'s)? ");
    private static final Pattern ENGLISH_STOP_PHRASE = Pattern.compile("(?i)(^| )('s|'re|is|are|were|was|do|does|did|don't|doesn't|didn't|has|have|be|there|you|me|your|my|mine|just|please|may|i|should|would|wouldn't|will|won't|done|go|for|with|so|the|a|an|by|i'm|it's|he's|she's|they|they're|you're|as|on|in|at|up|out|down|of|to|or|and|if) ");

    private static final Set<String> STOP_WORDS = Set.of(
            "请问", "您", "你", "我", "他", "是", "的", "就", "有", "于", "及", "即", "在", "为", "最",
            "从", "以", "了", "将", "与", "吗", "吧", "中", "#", "什么", "怎么", "哪个", "哪些", "啥", "相关",
            "和",
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in", "is", "of", "on",
            "or", "the", "to", "what", "which", "who", "why", "with"
    );

    private final JiebaSegmenter jiebaSegmenter = new JiebaSegmenter();

    @Value("${keyword.english-tokenizer-path:}")
    private String englishTokenizerPath;

    private HuggingFaceTokenizer englishTokenizer;

    @PostConstruct
    void init() {
        if (!StringUtils.hasText(englishTokenizerPath)) {
            return;
        }
        Path path = Path.of(englishTokenizerPath);
        if (!Files.exists(path)) {
            throw new BizException("英文 Hugging Face tokenizer 文件不存在：" + englishTokenizerPath);
        }
        try {
            englishTokenizer = HuggingFaceTokenizer.newInstance(path);
        } catch (IOException exception) {
            throw new BizException("英文 Hugging Face tokenizer 初始化失败：" + exception.getMessage());
        }
    }

    @PreDestroy
    void close() {
        if (englishTokenizer != null) {
            englishTokenizer.close();
        }
    }

    public List<String> extract(String text) {
        return extract(text, DEFAULT_TOP_N);
    }

    public List<String> extract(String text, int topN) {
        if (!StringUtils.hasText(text) || topN <= 0) {
            return List.of();
        }

        List<String> tokens = tokenMerge(pretoken(cleanQuestion(text), true));
        if (tokens.isEmpty()) {
            return List.of();
        }

        Map<String, Double> weights = new LinkedHashMap<>();
        int size = tokens.size();
        for (int i = 0; i < size; i++) {
            String token = tokens.get(i);
            double score = termWeight(token, i, size);
            weights.merge(token, score, Double::sum);
        }

        return weights.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }

    public double similarity(List<String> queryKeywords, List<String> documentKeywords) {
        if (queryKeywords == null || queryKeywords.isEmpty() || documentKeywords == null || documentKeywords.isEmpty()) {
            return 0;
        }
        Set<String> documentSet = new LinkedHashSet<>(documentKeywords);
        double matched = 0;
        double total = 0;
        for (int i = 0; i < queryKeywords.size(); i++) {
            String keyword = queryKeywords.get(i);
            double weight = queryKeywords.size() - i;
            total += weight;
            if (documentSet.contains(keyword)) {
                matched += weight;
            }
        }
        return total == 0 ? 0 : matched / total;
    }

    private List<String> pretoken(String text, boolean keepNumbers) {
        List<String> terms = new ArrayList<>();
        for (String piece : splitMixedLanguage(text)) {
            String normalized = normalizeToken(piece);
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            if (STOP_WORDS.contains(normalized) || (!keepNumbers && NUMBER.matcher(normalized).matches())) {
                continue;
            }
            if (!PUNCTUATION.matcher(normalized).matches()) {
                terms.add(normalized);
            }
        }
        return terms;
    }

    private List<String> splitMixedLanguage(String text) {
        List<String> terms = new ArrayList<>();
        StringBuilder chinese = new StringBuilder();
        StringBuilder english = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isChinese(ch)) {
                flushEnglish(english, terms);
                chinese.append(ch);
            } else if (isEnglishLike(ch)) {
                flushChinese(chinese, terms);
                english.append(ch);
            } else {
                flushChinese(chinese, terms);
                flushEnglish(english, terms);
                if (!Character.isWhitespace(ch)) {
                    terms.add(String.valueOf(ch));
                }
            }
        }
        flushChinese(chinese, terms);
        flushEnglish(english, terms);
        return terms;
    }

    private void flushChinese(StringBuilder buffer, List<String> terms) {
        if (buffer.isEmpty()) {
            return;
        }
        jiebaSegmenter.process(buffer.toString(), JiebaSegmenter.SegMode.INDEX)
                .stream()
                .map(token -> token.word)
                .filter(StringUtils::hasText)
                .forEach(terms::add);
        buffer.setLength(0);
    }

    private void flushEnglish(StringBuilder buffer, List<String> terms) {
        if (buffer.isEmpty()) {
            return;
        }
        String segment = buffer.toString().toLowerCase(Locale.ROOT);
        if (englishTokenizer != null) {
            terms.addAll(mergeHuggingFaceTokens(englishTokenizer.encode(segment).getTokens()));
        } else {
            ENGLISH_WORD.matcher(segment).results()
                    .map(match -> match.group().toLowerCase(Locale.ROOT))
                    .forEach(terms::add);
        }
        buffer.setLength(0);
    }

    private List<String> mergeHuggingFaceTokens(String[] tokens) {
        List<String> terms = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String token : tokens) {
            if (!StringUtils.hasText(token) || token.startsWith("[") || token.startsWith("<")) {
                continue;
            }
            if (token.startsWith("##")) {
                current.append(normalizeHuggingFaceToken(token));
                continue;
            }
            flushCurrentEnglishToken(current, terms);
            current.append(normalizeHuggingFaceToken(token));
        }
        flushCurrentEnglishToken(current, terms);
        return terms;
    }

    private void flushCurrentEnglishToken(StringBuilder current, List<String> terms) {
        if (current.isEmpty()) {
            return;
        }
        String normalized = current.toString();
        if (StringUtils.hasText(normalized)) {
            terms.add(normalized);
        }
        current.setLength(0);
    }

    private List<String> tokenMerge(List<String> tokens) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            int j = i;
            while (j < tokens.size() && isOneTerm(tokens.get(j)) && !STOP_WORDS.contains(tokens.get(j))) {
                j++;
            }
            if (j - i > 1) {
                int end = Math.min(j, i + 4);
                result.add(String.join(" ", tokens.subList(i, end)));
                i = end;
            } else {
                result.add(tokens.get(i));
                i++;
            }
        }
        return result.stream().filter(StringUtils::hasText).toList();
    }

    private double termWeight(String token, int position, int total) {
        double lengthWeight = Math.min(3.0, Math.max(1.0, token.length() / 2.0));
        double typeWeight = NUMBER.matcher(token).matches() ? 2.0 : 1.0;
        if (CHINESE_CHAR.matcher(token).find()) {
            typeWeight += 0.6;
        }
        if (ENGLISH_WORD.matcher(token).matches() && token.length() <= 2) {
            typeWeight *= 0.2;
        }
        double positionWeight = 1.0 + (total - position) / (double) Math.max(total, 1) * 0.2;
        return lengthWeight * typeWeight * positionWeight;
    }

    private String cleanQuestion(String text) {
        String cleaned = text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\r\\n\\t,，。？?/`!！&^%()\\[\\]{}<>:|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        cleaned = QUESTION_WORDS_ZH.matcher(cleaned).replaceAll("");
        cleaned = QUESTION_WORDS_EN.matcher(cleaned).replaceAll(" ");
        cleaned = ENGLISH_STOP_PHRASE.matcher(cleaned).replaceAll(" ");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private String normalizeToken(String token) {
        String normalized = normalizeHuggingFaceToken(token).trim();
        normalized = normalized.replaceAll("^[+\\-]+", "");
        normalized = normalized.replaceAll("[\\\\\"']+", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeHuggingFaceToken(String token) {
        return token.replace("##", "")
                .replace("Ġ", "")
                .replace("▁", "")
                .replace("[CLS]", "")
                .replace("[SEP]", "")
                .replace("[PAD]", "")
                .replace("[UNK]", "")
                .trim();
    }

    private boolean isOneTerm(String token) {
        return token.length() == 1 || token.matches("[0-9a-z]{1,2}");
    }

    private boolean isChinese(char ch) {
        return Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
    }

    private boolean isEnglishLike(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '+' || ch == '#' || ch == '.' || ch == '-';
    }

    public List<String> topKeywords(Map<String, Double> weightedKeywords, int topN) {
        if (weightedKeywords == null || weightedKeywords.isEmpty() || topN <= 0) {
            return List.of();
        }
        return weightedKeywords.entrySet()
                .stream()
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }
}
