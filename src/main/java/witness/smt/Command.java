package witness.smt;

import java.util.List;

/** Parsed SMT-LIB command plus the original text and index in the command stream. */
public abstract class Command {
    public final int index;
    public final String raw;
    public final String comments;

    Command(int index, String raw, String comments) {
        this.index = index;
        this.raw = raw;
        this.comments = comments;
    }

    public abstract String name();

    public static final class SetLogic extends Command {
        public final String logic;
        public SetLogic(int i, String raw, String comments, String logic) { super(i, raw, comments); this.logic = logic; }
        public String name() { return "set-logic"; }
    }
    public static final class DeclareSort extends Command {
        public final String sortName; public final int arity;
        public DeclareSort(int i, String raw, String c, String n, int arity) { super(i, raw, c); this.sortName = n; this.arity = arity; }
        public String name() { return "declare-sort"; }
    }
    public static final class DefineSort extends Command {
        public final String sortName; public final Sort body;
        public DefineSort(int i, String raw, String c, String n, Sort body) { super(i, raw, c); this.sortName = n; this.body = body; }
        public String name() { return "define-sort"; }
    }
    public static final class DeclareFun extends Command {
        public final String fun; public final List<Sort> params; public final Sort result;
        public DeclareFun(int i, String raw, String c, String fun, List<Sort> params, Sort result) {
            super(i, raw, c); this.fun = fun; this.params = params; this.result = result;
        }
        public String name() { return "declare-fun"; }
    }
    public static final class DefineFun extends Command {
        public final String fun; public final List<Param> params; public final Sort result; public final Term body;
        public DefineFun(int i, String raw, String c, String fun, List<Param> params, Sort result, Term body) {
            super(i, raw, c); this.fun = fun; this.params = params; this.result = result; this.body = body;
        }
        public String name() { return "define-fun"; }
    }
    public static final class Param {
        public final String name; public final Sort sort;
        public Param(String name, Sort sort) { this.name = name; this.sort = sort; }
    }
    public static final class DefineFunRec extends DefineFun {
        public DefineFunRec(int i, String raw, String c, String fun, List<Param> params, Sort result, Term body) {
            super(i, raw, c, fun, params, result, body);
        }
        @Override public String name() { return "define-fun-rec"; }
    }
    public static final class Assert extends Command {
        public final Term term; public final String named;
        public Assert(int i, String raw, String c, Term term, String named) { super(i, raw, c); this.term = term; this.named = named; }
        public String name() { return "assert"; }
    }
    public static final class Push extends Command {
        public final int levels;
        public Push(int i, String raw, String c, int levels) { super(i, raw, c); this.levels = levels; }
        public String name() { return "push"; }
    }
    public static final class Pop extends Command {
        public final int levels;
        public Pop(int i, String raw, String c, int levels) { super(i, raw, c); this.levels = levels; }
        public String name() { return "pop"; }
    }
    public static final class CheckSat extends Command {
        public CheckSat(int i, String raw, String c) { super(i, raw, c); }
        public String name() { return "check-sat"; }
    }
    public static final class CheckSatAssuming extends Command {
        public final List<String> assumptions;
        public CheckSatAssuming(int i, String raw, String c, List<String> assumptions) { super(i, raw, c); this.assumptions = assumptions; }
        public String name() { return "check-sat-assuming"; }
    }
    public static final class GetModel extends Command {
        public GetModel(int i, String raw, String c) { super(i, raw, c); }
        public String name() { return "get-model"; }
    }
    public static final class GetUnsatCore extends Command {
        public GetUnsatCore(int i, String raw, String c) { super(i, raw, c); }
        public String name() { return "get-unsat-core"; }
    }
    /** Declare-datatype for simple zero-arity enums: (declare-datatype Color ((A) (B))). */
    public static final class DeclareDatatype extends Command {
        public final String sortName; public final List<String> ctors;
        public DeclareDatatype(int i, String raw, String c, String sortName, List<String> ctors) {
            super(i, raw, c); this.sortName = sortName; this.ctors = ctors;
        }
        public String name() { return "declare-datatype"; }
    }
    public static final class SetOption extends Command {
        public final String option; public final String value;
        public SetOption(int i, String raw, String c, String option, String value) { super(i, raw, c); this.option = option; this.value = value; }
        public String name() { return "set-option"; }
    }
    public static final class Echo extends Command {
        public final String text;
        public Echo(int i, String raw, String c, String text) { super(i, raw, c); this.text = text; }
        public String name() { return "echo"; }
    }
    /** Command the tool records but does not execute semantically (e.g. set-info, exit). */
    public static final class Other extends Command {
        public final String kind;
        public Other(int i, String raw, String c, String kind) { super(i, raw, c); this.kind = kind; }
        public String name() { return kind; }
    }
}
