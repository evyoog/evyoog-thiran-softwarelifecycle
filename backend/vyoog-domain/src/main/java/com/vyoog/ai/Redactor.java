package com.vyoog.ai;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * VYB-0937 (F27): takes secrets out of text, and replaces personal data with tokens, before the text goes to a model
 * provider. Pure: no database, no network; what to switch off and who the known people are come in as arguments.
 *
 * <p>What it finds is what a pattern can find, and no more:
 * <ul>
 *   <li><b>Secrets</b> (always removed, replaced by {@value #SECRET_MARKER}, never restored): private-key blocks,
 *       AWS, GitHub, Slack, Google, Stripe and {@code sk-} style keys, JWTs, {@code Bearer} tokens, the user and
 *       password in a URL, and the value of a {@code password}, {@code secret}, {@code token}, {@code api key} or
 *       similar assignment when the value looks like a credential (six or more characters with a digit or symbol).</li>
 *   <li><b>Card numbers</b>: 13 to 19 digits that pass the Luhn check, with optional spaces or dashes.</li>
 *   <li><b>Email addresses.</b></li>
 *   <li><b>IPv4 addresses</b> (so a version number written like one, "1.2.3.4", is also taken).</li>
 *   <li><b>Phone numbers</b> in three shapes only: international with a leading {@code +}, a bracketed area code
 *       {@code (415) 555-2671}, and {@code 415-555-2671} or {@code 98765 43210}. A bare run of digits is not a phone
 *       number here, because a requirement is full of numbers.</li>
 *   <li><b>People</b>: the display names of the people passed in, matched exactly, whole words, any case. A name
 *       of someone who is not in that list is not found; that needs a name-recognition model.</li>
 * </ul>
 * The same value in one text gets the same token, so the provider can still tell that two mentions are one thing.
 */
@Component
public class Redactor {

    public static final String SECRET_MARKER = "[REDACTED-SECRET]";

    /** A name shorter than this is not matched: it would hit inside ordinary words. */
    static final int MIN_NAME_LENGTH = 3;

    private static final Pattern[] SECRET_VALUE_PATTERNS = {
        Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[A-Za-z0-9+/=\\s]{20,}"), // a block with no end line
        Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
        Pattern.compile("\\b(?:sk|pk|rk)-[A-Za-z0-9_-]{20,}"),
        Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}"),
        Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}"),
        Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}"),
        Pattern.compile("\\b[sr]k_(?:live|test)_[0-9A-Za-z]{16,}"),
        Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
        Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
    };
    /** scheme://user:password@host: the user and password go, the rest stays. */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]{1,30}://)[^\\s/:@]{1,200}:[^\\s/@]{1,200}@");
    /** name = value, where the name says it is a credential and the value looks like one. */
    private static final Pattern ASSIGNMENT = Pattern.compile(
        "(?i)(\\b[A-Za-z0-9_.-]{0,40}(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?key|private[_-]?key|token|credential)s?[A-Za-z0-9_.-]{0,40}"
        + "[\"']?\\s{0,3}[:=]\\s{0,3}[\"']?)(?=[^\\s\"',;]{0,200}[0-9!@#$%^&*_+/=-])[^\\s\"',;]{6,200}");

    private static final Pattern CARD = Pattern.compile("(?<![\\d-])(?:\\d[ -]?){12,18}\\d(?![\\d-])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){1,8}");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b");
    private static final Pattern PHONE = Pattern.compile(
        "(?<![\\w.+-])(?:\\+\\d{1,3}[ .-]?(?:\\(?\\d{1,4}\\)?[ .-]?){1,4}\\d{2,4}"
        + "|\\(\\d{2,4}\\)[ .-]?\\d{3,4}[ .-]?\\d{3,4}"
        + "|\\d{3}[ .-]\\d{3}[ .-]\\d{4}"
        + "|\\d{5}[ .-]\\d{5})(?![\\w-])");

    /**
     * @param disabled the classes an administrator has switched off; {@link DataClass#SECRET} in it is ignored
     * @param people   display names to treat as {@link DataClass#PERSON}; only read if PERSON is on
     */
    public Redaction redact(String text, Set<DataClass> disabled, Collection<String> people) {
        return redact(text, disabled, people == null || people.isEmpty() ? null : nameMatcher(people));
    }

    /** As above, with the names already compiled by {@link #nameMatcher}. */
    public Redaction redact(String text, Set<DataClass> disabled, Pattern names) {
        if (text == null || text.isEmpty()) return new Redaction(text, Map.of(), Map.of());
        Map<String, String> tokens = new LinkedHashMap<>();
        Map<DataClass, Integer> counts = new EnumMap<>(DataClass.class);
        Map<DataClass, Map<String, String>> seen = new EnumMap<>(DataClass.class);

        String out = removeSecrets(text, counts);
        if (on(DataClass.CARD, disabled)) out = tokenise(out, CARD, DataClass.CARD, tokens, counts, seen, m -> luhn(m.group()));
        if (on(DataClass.EMAIL, disabled)) out = tokenise(out, EMAIL, DataClass.EMAIL, tokens, counts, seen, m -> true);
        if (on(DataClass.PERSON, disabled) && names != null) out = tokenise(out, names, DataClass.PERSON, tokens, counts, seen, m -> true);
        if (on(DataClass.IP_ADDRESS, disabled)) out = tokenise(out, IPV4, DataClass.IP_ADDRESS, tokens, counts, seen, m -> true);
        if (on(DataClass.PHONE, disabled)) out = tokenise(out, PHONE, DataClass.PHONE, tokens, counts, seen, m -> digits(m.group()) >= 10 && digits(m.group()) <= 15);
        return new Redaction(out, tokens, counts);
    }

    /** The one pattern that finds any of these names as whole words, in any case. Null if there are none usable. */
    public Pattern nameMatcher(Collection<String> people) {
        StringBuilder alternation = new StringBuilder();
        people.stream()
            .filter(n -> n != null && n.strip().codePointCount(0, n.strip().length()) >= MIN_NAME_LENGTH)
            .map(String::strip).distinct()
            .sorted((a, b) -> b.length() - a.length()) // the longest first, so "Ann Lee" wins over "Ann"
            .forEach(n -> {
                if (alternation.length() > 0) alternation.append('|');
                alternation.append(String.join("\\s+", java.util.Arrays.stream(n.split("\\s+")).map(Pattern::quote).toList()));
            });
        if (alternation.length() == 0) return null;
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternation + ")(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static boolean on(DataClass c, Set<DataClass> disabled) {
        return !disabled.contains(c);
    }

    private static String removeSecrets(String text, Map<DataClass, Integer> counts) {
        String out = text;
        for (Pattern p : SECRET_VALUE_PATTERNS) out = replaceWithMarker(out, p, counts);
        out = replaceWith(out, URL_CREDENTIALS, m -> m.group(1) + SECRET_MARKER + "@", counts);
        out = replaceWith(out, ASSIGNMENT, m -> m.group(1) + SECRET_MARKER, counts);
        return out;
    }

    private static String replaceWithMarker(String text, Pattern p, Map<DataClass, Integer> counts) {
        return replaceWith(text, p, m -> SECRET_MARKER, counts);
    }

    private static String replaceWith(String text, Pattern p, java.util.function.Function<Matcher, String> replacement, Map<DataClass, Integer> counts) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement.apply(m)));
            counts.merge(DataClass.SECRET, 1, Integer::sum);
        }
        return m.appendTail(sb).toString();
    }

    private static String tokenise(String text, Pattern p, DataClass cls, Map<String, String> tokens, Map<DataClass, Integer> counts,
                                   Map<DataClass, Map<String, String>> seen, java.util.function.Predicate<Matcher> accept) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (!accept.test(m)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            Map<String, String> forClass = seen.computeIfAbsent(cls, k -> new LinkedHashMap<>());
            String value = m.group();
            String token = forClass.get(value);
            if (token == null) {
                token = "[" + cls.tokenName() + "_" + (forClass.size() + 1) + "]";
                forClass.put(value, token);
                tokens.put(token, value);
            }
            counts.merge(cls, 1, Integer::sum);
            m.appendReplacement(sb, Matcher.quoteReplacement(token));
        }
        return m.appendTail(sb).toString();
    }

    private static int digits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) n++;
        return n;
    }

    /** The Luhn check, so a long number that is not a card number (an id, a timestamp) is left alone. */
    static boolean luhn(String candidate) {
        int sum = 0;
        int count = 0;
        boolean alternate = false;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (!Character.isDigit(c)) continue;
            int d = c - '0';
            if (alternate) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            alternate = !alternate;
            count++;
        }
        return count >= 13 && count <= 19 && sum % 10 == 0;
    }
}
