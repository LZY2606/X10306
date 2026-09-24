package witnessroom.smt;

import witnessroom.smt.SmtLexer.Token;

import java.util.ArrayList;
import java.util.List;

/** Recursive descent parser turning a token stream into S-expressions. */
public final class SmtParser {

    private final List<Token> tokens;
    private int pos;

    public SmtParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static List<SExpr> parseAll(String text) {
        SmtParser p = new SmtParser(SmtLexer.lex(text));
        List<SExpr> out = new ArrayList<>();
        while (p.pos < p.tokens.size()) {
            out.add(p.parseOne());
        }
        return out;
    }

    public static SExpr parseOne(String text) {
        List<SExpr> all = parseAll(text);
        if (all.size() != 1) {
            throw new SmtParseException("expected exactly one command, got " + all.size());
        }
        return all.get(0);
    }

    /** Parse a single pre-sliced top-level command; fails if it is not a list. */
    public SExpr.List parseExternal() {
        if (tokens.isEmpty()) {
            throw new SmtParseException("empty command");
        }
        SExpr e = parseOne();
        if (pos != tokens.size()) {
            Token t = tokens.get(pos);
            throw new SmtParseException("unexpected trailing token " + t.text(), t.line(), t.col());
        }
        if (e instanceof SExpr.List l) return l;
        throw new SmtParseException("top-level S-expressions must be parenthesised commands: " + e.raw());
    }

    private SExpr parseOne() {
        Token t = tokens.get(pos);
        if (t.isClose()) {
            throw new SmtParseException("unexpected ')'", t.line(), t.col());
        }
        if (t.isOpen()) {
            pos++;
            List<SExpr> kids = new ArrayList<>();
            while (pos < tokens.size() && !tokens.get(pos).isClose()) {
                kids.add(parseOne());
            }
            if (pos >= tokens.size()) {
                throw new SmtParseException("unterminated list: missing ')'", t.line(), t.col());
            }
            pos++;
            return new SExpr.List(kids);
        }
        pos++;
        return new SExpr.Atom(t.text());
    }
}
