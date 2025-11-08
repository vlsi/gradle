/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.util.internal;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.UncheckedException;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

public class TextUtil {
    private static final Pattern WHITESPACE = Pattern.compile("\\s*");
    private static final Pattern UPPER_CASE = Pattern.compile("(?=\\p{Upper})");
    private static final Joiner KEBAB_JOINER = Joiner.on("-");
    private static final Function<String, String> TO_LOWERCASE = new Function<String, String>() {
        @Override
        public String apply(String input) {
            return input.toLowerCase(Locale.ROOT);
        }
    };
    private static final Pattern NON_UNIX_LINE_SEPARATORS = Pattern.compile("\r\n|\r");

    /**
     * Returns the line separator for Windows.
     */
    public static String getWindowsLineSeparator() {
        return "\r\n";
    }

    /**
     * Returns the line separator for Unix.
     */
    public static String getUnixLineSeparator() {
        return "\n";
    }

    /**
     * Returns the line separator for this platform.
     */
    public static String getPlatformLineSeparator() {
        return SystemProperties.getInstance().getLineSeparator();
    }

    /**
     * Converts all line separators in the specified string to the specified line separator.
     */
    @Nullable
    public static String convertLineSeparators(@Nullable String str, String sep) {
        return str == null ? null : replaceLineSeparatorsOf(str, sep);
    }

    /**
     * Converts all line separators in the specified non-null string to the Unix line separator {@code \n}.
     */
    public static String convertLineSeparatorsToUnix(String str) {
        return replaceAll(NON_UNIX_LINE_SEPARATORS, str, "\n");
    }

    /**
     * Converts all line separators in the specified non-null {@link CharSequence} to the specified line separator.
     */
    public static String replaceLineSeparatorsOf(CharSequence string, String bySeparator) {
        return replaceAll("\r\n|\r|\n", string, bySeparator);
    }

    private static String replaceAll(String regex, CharSequence inString, String byString) {
        return replaceAll(Pattern.compile(regex), inString, byString);
    }

    private static String replaceAll(Pattern pattern, CharSequence inString, String byString) {
        return pattern.matcher(inString).replaceAll(byString);
    }

    /**
     * Converts all line separators in the specified string to the platform's line separator.
     */
    public static String toPlatformLineSeparators(String str) {
        return str == null ? null : replaceLineSeparatorsOf(str, getPlatformLineSeparator());
    }

    /**
     * Converts all line separators in the specified nullable string to a single new line character ({@code \n}).
     *
     * @return null if the given string is null
     */
    @Nullable
    public static String normaliseLineSeparators(@Nullable String str) {
        return str == null ? null : convertLineSeparatorsToUnix(str);
    }

    /**
     * Converts all native file separators in the specified string to '/'.
     */
    public static String normaliseFileSeparators(String path) {
        return path.replace(File.separatorChar, '/');
    }

    /**
     * Checks if a String is whitespace, empty ("") or null.
     * <p>
     * This function was copied from Apache Commons-Lang to restore TAPI compatibility
     * with Java 6 on old Gradle versions because StringUtils require SQLException which
     * is absent from Java 6.
     */
    public static boolean isBlank(String str) {
        int strLen;
        if (str == null || (strLen = str.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Capitalizes a String changing the first letter to title case as
     * per {@link Character#toTitleCase(char)}. No other letters are changed.
     * <p>
     * This function was copied from Apache Commons-Lang to restore TAPI compatibility
     * with Java 6 on old Gradle versions because StringUtils require SQLException which
     * is absent from Java 6.
     */
    public static String capitalize(String str) {
        if (str == null || str.length() == 0) {
            return str;
        }
        return Character.toTitleCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * Escapes the toString() representation of {@code obj} for use in a literal string.
     * This is useful for interpolating variables into script strings, as well as in other situations.
     */
    public static String escapeString(Object obj) {
        return obj == null ? null : escapeJavaStyleString(obj.toString(), false, false);
    }

    /**
     * This function was copied from Apache Commons-Lang to restore TAPI compatibility
     * with Java 6 on old Gradle versions because StringUtils require SQLException which
     * is absent from Java 6.
     */
    private static String escapeJavaStyleString(String str, boolean escapeSingleQuotes, boolean escapeForwardSlash) {
        if (str == null) {
            return null;
        }
        try {
            StringWriter writer = new StringWriter(str.length() * 2);
            escapeJavaStyleString(writer, str, escapeSingleQuotes, escapeForwardSlash);
            return writer.toString();
        } catch (IOException ioe) {
            // this should never ever happen while writing to a StringWriter
            throw UncheckedException.throwAsUncheckedException(ioe);
        }
    }


    private static void escapeJavaStyleString(
        Writer out, String str, boolean escapeSingleQuote,
        boolean escapeForwardSlash
    ) throws IOException {
        if (out == null) {
            throw new IllegalArgumentException("The Writer must not be null");
        }
        if (str == null) {
            return;
        }
        int sz;
        sz = str.length();
        for (int i = 0; i < sz; i++) {
            char ch = str.charAt(i);

            // handle unicode
            if (ch > 0xfff) {
                out.write("\\u" + hex(ch));
            } else if (ch > 0xff) {
                out.write("\\u0" + hex(ch));
            } else if (ch > 0x7f) {
                out.write("\\u00" + hex(ch));
            } else if (ch < 32) {
                switch (ch) {
                    case '\b':
                        out.write('\\');
                        out.write('b');
                        break;
                    case '\n':
                        out.write('\\');
                        out.write('n');
                        break;
                    case '\t':
                        out.write('\\');
                        out.write('t');
                        break;
                    case '\f':
                        out.write('\\');
                        out.write('f');
                        break;
                    case '\r':
                        out.write('\\');
                        out.write('r');
                        break;
                    default:
                        if (ch > 0xf) {
                            out.write("\\u00" + hex(ch));
                        } else {
                            out.write("\\u000" + hex(ch));
                        }
                        break;
                }
            } else {
                switch (ch) {
                    case '\'':
                        if (escapeSingleQuote) {
                            out.write('\\');
                        }
                        out.write('\'');
                        break;
                    case '"':
                        out.write('\\');
                        out.write('"');
                        break;
                    case '\\':
                        out.write('\\');
                        out.write('\\');
                        break;
                    case '/':
                        if (escapeForwardSlash) {
                            out.write('\\');
                        }
                        out.write('/');
                        break;
                    default:
                        out.write(ch);
                        break;
                }
            }
        }
    }

    private static String hex(char ch) {
        return Integer.toHexString(ch).toUpperCase(Locale.ENGLISH);
    }

    /**
     * Tells whether the specified string contains any whitespace characters.
     */
    public static boolean containsWhitespace(String str) {
        for (int i = 0; i < str.length(); i++) {
            if (Character.isWhitespace(str.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Indents every line of {@code text} by {@code indent}. Empty lines
     * and lines that only contain whitespace are not indented.
     */
    public static String indent(String text, String indent) {
        StringBuilder builder = new StringBuilder();
        String[] lines = text.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!WHITESPACE.matcher(line).matches()) {
                builder.append(indent);
            }
            builder.append(line);
            if (i < lines.length - 1) {
                builder.append('\n');
            }
        }

        return builder.toString();
    }

    public static String shorterOf(String s1, String s2) {
        if (s2.length() >= s1.length()) {
            return s1;
        } else {
            return s2;
        }
    }

    /**
     * Same behavior as Groovy minus operator between Strings
     *
     * @param originalString original string
     * @param removeString string to remove
     * @return string with removeString removed or the original string if it did not contain removeString
     */
    public static String minus(String originalString, String removeString) {
        String s = originalString.toString();
        int index = s.indexOf(removeString);
        if (index == -1) {
            return s;
        }
        int end = index + removeString.length();
        if (s.length() > end) {
            return s.substring(0, index) + s.substring(end);
        }
        return s.substring(0, index);
    }

    public static String normaliseFileAndLineSeparators(String in) {
        return normaliseLineSeparators(normaliseFileSeparators(in));
    }

    public static String camelToKebabCase(String camelCase) {
        return KEBAB_JOINER.join(Iterables.transform(Arrays.asList(UPPER_CASE.split(camelCase)), TO_LOWERCASE));
    }

    /**
     * This method returns the plural ending for an english word for trivial cases depending on the number of elements a list has.
     *
     * @param collection which size is used to determine the plural ending
     * @return "s" if the collection has more than one element, an empty string otherwise
     */
    public static String getPluralEnding(Collection<?> collection) {
        return collection.size() > 1 ? "s" : "";
    }

    public static String endLineWithDot(String txt) {
        if (txt.endsWith(".") || txt.endsWith("\n")) {
            return txt;
        }
        return txt + ".";
    }

    public static String screamingSnakeToKebabCase(String text) {
        return replace(text.toLowerCase(Locale.ENGLISH), "_", "-");
    }

    /**
     * Replaces all occurrences of a String within another String.
     */
    public static String replace(String text, String searchString, String replacement) {
        if (text == null || text.isEmpty() || searchString == null || searchString.isEmpty()) {
            return text;
        }
        return text.replace(searchString, replacement);
    }

    public static String removeTrailing(String originalString, String suffix) {
        if (originalString.endsWith(suffix)) {
            return originalString.substring(0, originalString.length() - suffix.length());
        }
        return originalString;
    }

    /**
     * Checks if a CharSequence is empty ("") or null.
     */
    public static boolean isEmpty(@Nullable CharSequence cs) {
        return cs == null || cs.length() == 0;
    }

    /**
     * Checks if a CharSequence is not empty ("") and not null.
     */
    public static boolean isNotEmpty(@Nullable CharSequence cs) {
        return !isEmpty(cs);
    }

    /**
     * Checks if a CharSequence is not empty (""), not null and not whitespace only.
     */
    public static boolean isNotBlank(@Nullable CharSequence cs) {
        return !isBlank(cs == null ? null : cs.toString());
    }

    /**
     * Uncapitalizes a String, changing the first character to lower case.
     */
    public static String uncapitalize(String str) {
        if (str == null || str.length() == 0) {
            return str;
        }
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * Gets a substring after the last occurrence of a separator.
     */
    @Nullable
    public static String substringAfterLast(@Nullable String str, String separator) {
        if (isEmpty(str)) {
            return str;
        }
        if (isEmpty(separator)) {
            return "";
        }
        int pos = str.lastIndexOf(separator);
        if (pos == -1 || pos == str.length() - separator.length()) {
            return "";
        }
        return str.substring(pos + separator.length());
    }

    /**
     * Gets a substring before the last occurrence of a separator.
     */
    @Nullable
    public static String substringBeforeLast(@Nullable String str, String separator) {
        if (isEmpty(str) || isEmpty(separator)) {
            return str;
        }
        int pos = str.lastIndexOf(separator);
        if (pos == -1) {
            return str;
        }
        return str.substring(0, pos);
    }

    /**
     * Gets the substring before the first occurrence of a separator.
     */
    @Nullable
    public static String substringBefore(@Nullable String str, String separator) {
        if (isEmpty(str) || separator == null) {
            return str;
        }
        if (separator.isEmpty()) {
            return "";
        }
        int pos = str.indexOf(separator);
        if (pos == -1) {
            return str;
        }
        return str.substring(0, pos);
    }

    /**
     * Gets the substring after the first occurrence of a separator.
     */
    @Nullable
    public static String substringAfter(@Nullable String str, String separator) {
        if (isEmpty(str)) {
            return str;
        }
        if (separator == null) {
            return "";
        }
        int pos = str.indexOf(separator);
        if (pos == -1) {
            return "";
        }
        return str.substring(pos + separator.length());
    }

    /**
     * Normalizes whitespace by trimming and replacing sequences of whitespace with a single space.
     */
    public static String normalizeSpace(@Nullable String str) {
        if (isEmpty(str)) {
            return str;
        }
        return str.trim().replaceAll("\\s+", " ");
    }

    /**
     * Counts how many times the substring appears in the larger string.
     */
    public static int countMatches(@Nullable CharSequence str, CharSequence sub) {
        if (isEmpty(str) || isEmpty(sub)) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        String strStr = str.toString();
        String subStr = sub.toString();
        while ((idx = strStr.indexOf(subStr, idx)) != -1) {
            count++;
            idx += subStr.length();
        }
        return count;
    }

    /**
     * Splits a String by a character, trimming each element and omitting empty strings.
     */
    public static String[] split(@Nullable String str, char separatorChar) {
        if (str == null) {
            return null;
        }
        if (str.isEmpty()) {
            return new String[0];
        }
        return str.split(Pattern.quote(String.valueOf(separatorChar)));
    }

    /**
     * Splits a String by a separator string.
     */
    public static String[] split(@Nullable String str, String separatorChars) {
        if (str == null) {
            return null;
        }
        if (str.isEmpty()) {
            return new String[0];
        }
        if (separatorChars == null) {
            return new String[]{str};
        }
        if (separatorChars.length() == 1) {
            return split(str, separatorChars.charAt(0));
        }
        // Build a regex pattern that treats each character as a separator
        StringBuilder pattern = new StringBuilder("[");
        for (char c : separatorChars.toCharArray()) {
            pattern.append(Pattern.quote(String.valueOf(c)));
        }
        pattern.append("]+");
        String[] parts = str.split(pattern.toString());
        // Filter out empty strings
        return Arrays.stream(parts).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    /**
     * Left pad a String with spaces.
     */
    public static String leftPad(String str, int size) {
        return leftPad(str, size, ' ');
    }

    /**
     * Left pad a String with a specified character.
     */
    public static String leftPad(String str, int size, char padChar) {
        if (str == null) {
            return null;
        }
        int pads = size - str.length();
        if (pads <= 0) {
            return str;
        }
        return repeat(padChar, pads) + str;
    }

    /**
     * Right pad a String with spaces.
     */
    public static String rightPad(String str, int size) {
        return rightPad(str, size, ' ');
    }

    /**
     * Right pad a String with a specified character.
     */
    public static String rightPad(String str, int size, char padChar) {
        if (str == null) {
            return null;
        }
        int pads = size - str.length();
        if (pads <= 0) {
            return str;
        }
        return str + repeat(padChar, pads);
    }

    /**
     * Repeat a character n times.
     */
    public static String repeat(char ch, int repeat) {
        if (repeat <= 0) {
            return "";
        }
        char[] buf = new char[repeat];
        Arrays.fill(buf, ch);
        return new String(buf);
    }

    /**
     * Repeat a String n times.
     */
    public static String repeat(String str, int repeat) {
        if (str == null) {
            return null;
        }
        if (repeat <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(str.length() * repeat);
        for (int i = 0; i < repeat; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    /**
     * Strips whitespace from the start and end of a String.
     */
    @Nullable
    public static String strip(@Nullable String str) {
        return str == null ? null : str.trim();
    }

    /**
     * Joins elements with a separator.
     */
    public static String join(Iterable<?> iterable, String separator) {
        if (iterable == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object obj : iterable) {
            if (!first) {
                sb.append(separator);
            }
            if (obj != null) {
                sb.append(obj);
            }
            first = false;
        }
        return sb.toString();
    }

    /**
     * Removes a substring only if it is at the start of a source string.
     */
    public static String removeStart(String str, String remove) {
        if (isEmpty(str) || isEmpty(remove)) {
            return str;
        }
        if (str.startsWith(remove)) {
            return str.substring(remove.length());
        }
        return str;
    }

    /**
     * Removes a substring only if it is at the end of a source string.
     */
    public static String removeEnd(String str, String remove) {
        if (isEmpty(str) || isEmpty(remove)) {
            return str;
        }
        if (str.endsWith(remove)) {
            return str.substring(0, str.length() - remove.length());
        }
        return str;
    }

    /**
     * Case insensitive check if a CharSequence contains a search CharSequence.
     */
    public static boolean containsIgnoreCase(CharSequence str, CharSequence searchStr) {
        if (str == null || searchStr == null) {
            return false;
        }
        return str.toString().toLowerCase(Locale.ENGLISH).contains(searchStr.toString().toLowerCase(Locale.ENGLISH));
    }

    /**
     * Checks if the CharSequence contains only lowercase characters.
     */
    public static boolean isAllLowerCase(CharSequence cs) {
        if (isEmpty(cs)) {
            return false;
        }
        for (int i = 0; i < cs.length(); i++) {
            if (!Character.isLowerCase(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds the Levenshtein distance between two Strings.
     */
    public static int getLevenshteinDistance(CharSequence s, CharSequence t) {
        if (s == null || t == null) {
            throw new IllegalArgumentException("Strings must not be null");
        }

        int n = s.length();
        int m = t.length();

        if (n == 0) {
            return m;
        } else if (m == 0) {
            return n;
        }

        if (n > m) {
            // swap the input strings to consume less memory
            CharSequence tmp = s;
            s = t;
            t = tmp;
            n = m;
            m = t.length();
        }

        int[] p = new int[n + 1];
        int[] d = new int[n + 1];
        int[] tempD;

        int i;
        int j;
        char tj;
        int cost;

        for (i = 0; i <= n; i++) {
            p[i] = i;
        }

        for (j = 1; j <= m; j++) {
            tj = t.charAt(j - 1);
            d[0] = j;

            for (i = 1; i <= n; i++) {
                cost = s.charAt(i - 1) == tj ? 0 : 1;
                d[i] = Math.min(Math.min(d[i - 1] + 1, p[i] + 1), p[i - 1] + cost);
            }

            tempD = p;
            p = d;
            d = tempD;
        }

        return p[n];
    }

    /**
     * Checks whether the String contains any character in the given set of characters.
     */
    public static boolean containsAny(CharSequence cs, char... searchChars) {
        if (isEmpty(cs) || searchChars == null || searchChars.length == 0) {
            return false;
        }
        for (int i = 0; i < cs.length(); i++) {
            char ch = cs.charAt(i);
            for (char searchChar : searchChars) {
                if (ch == searchChar) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether the String contains any character in the given set of characters.
     */
    public static boolean containsAny(CharSequence cs, String searchChars) {
        if (searchChars == null) {
            return false;
        }
        return containsAny(cs, searchChars.toCharArray());
    }

    /**
     * Strips any of a set of characters from the start of a String.
     */
    public static String stripStart(String str, String stripChars) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        if (stripChars == null) {
            return str.replaceAll("^\\s+", "");
        }
        int start = 0;
        while (start < str.length() && stripChars.indexOf(str.charAt(start)) != -1) {
            start++;
        }
        return str.substring(start);
    }

    /**
     * Strips any of a set of characters from the end of a String.
     */
    public static String stripEnd(String str, String stripChars) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        if (stripChars == null) {
            return str.replaceAll("\\s+$", "");
        }
        int end = str.length();
        while (end > 0 && stripChars.indexOf(str.charAt(end - 1)) != -1) {
            end--;
        }
        return str.substring(0, end);
    }

    /**
     * Finds the n-th index of a substring within a string.
     */
    public static int ordinalIndexOf(CharSequence str, CharSequence searchStr, int ordinal) {
        if (str == null || searchStr == null || ordinal <= 0) {
            return -1;
        }
        if (searchStr.length() == 0) {
            return 0;
        }
        int found = 0;
        int index = -1;
        do {
            index = str.toString().indexOf(searchStr.toString(), index + 1);
            if (index < 0) {
                return index;
            }
            found++;
        } while (found < ordinal);
        return index;
    }

    /**
     * Splits a String preserving all tokens, including empty tokens created by adjacent separators.
     */
    public static String[] splitPreserveAllTokens(String str, String separatorChars) {
        if (str == null) {
            return null;
        }
        if (str.isEmpty()) {
            return new String[0];
        }
        if (separatorChars == null) {
            separatorChars = " ";
        }
        // Build a regex pattern that treats each character as a separator, but preserve empty tokens
        StringBuilder pattern = new StringBuilder("[");
        for (char c : separatorChars.toCharArray()) {
            pattern.append(Pattern.quote(String.valueOf(c)));
        }
        pattern.append("]");
        return str.split(pattern.toString(), -1);
    }
}
