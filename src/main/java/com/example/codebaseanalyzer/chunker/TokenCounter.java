package com.example.codebaseanalyzer.chunker;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Counts tokens using jtokkit's cl100k_base encoding (the same BPE encoding OpenAI's
 * tiktoken uses) as a practical proxy for Claude's tokenizer -- Anthropic does not
 * publish a public local tokenizer, so this errs on the side of over-counting, which
 * keeps chunks safely under real limits rather than risking an overflow.
 */
public final class TokenCounter {

    private static final Encoding ENCODING;

    static {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        ENCODING = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    private TokenCounter() { }

    public static int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        return ENCODING.countTokens(text);
    }

    /** Splits text into token-bounded slices without regard to syntax -- used only as a
     * last-resort hard split when a language-aware split still leaves an oversized piece.
     *
     * Deliberately implemented using only {@code countTokens(String)} (binary search over
     * character offsets) rather than jtokkit's {@code encode()}/{@code decode()} token-index
     * slicing: this keeps the code robust to the exact generic list type those methods
     * return, which varies between jtokkit versions. */
    public static java.util.List<String> hardSplitByTokens(String text, int maxTokensPerPiece) {
        java.util.List<String> pieces = new java.util.ArrayList<>();
        int start = 0;
        int n = text.length();
        while (start < n) {
            int lo = start + 1, hi = n, best = start + 1;
            while (lo <= hi) {
                int mid = (lo + hi) / 2;
                if (count(text.substring(start, mid)) <= maxTokensPerPiece) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            pieces.add(text.substring(start, best));
            start = best;
        }
        return pieces;
    }
}
