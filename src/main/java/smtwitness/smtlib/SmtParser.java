package smtwitness.smtlib;

import java.util.ArrayList;
import java.util.List;

/** Parses a token stream into fully parenthesized S-expressions. */
public final class SmtParser {
    private final List<String> tokens;
    private int idx;

    private SmtParser(List<String> tokens) {
        this.tokens = tokens;
    }

    public static List<SExpr> parseAll(String src) {
        List<String> toks = SmtLexer.tokenize(src);
        SmtParser p = new SmtParser(toks);
        List<SExpr> forms = new ArrayList<>();
        while (p.idx < toks.size()) {
            forms.add(p.readExpr());
        }
        return forms;
    }

    private SExpr readExpr() {
        String tok = tokens.get(idx++);
        if (tok.equals(")")) {
            throw new SmtParseException("unexpected ')' at token " + idx);
        }
        if (!tok.equals("(")) {
            return new SExpr.Atom(tok);
        }
        List<SExpr> children = new ArrayList<>();
        while (!tokens.get(idx).equals(")")) {
            children.add(readExpr());
        }
        idx++; // consume )
        return new SExpr.List(children);
    }
}
