package room.testimony;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an SMT-LIB command stream plus the solver response stream.
 * Raw text of every command and response is preserved; parse problems become
 * diagnostics attached to the session instead of dropping the input.
 */
public final class Script {
    public enum Kind {
        SET_LOGIC, SET_INFO, DECLARE, ASSERT, PUSH, POP,
        CHECK_SAT, CHECK_SAT_ASSUMING, GET_MODEL, GET_UNSAT_CORE, GET_VALUE, OTHER
    }

    public record Command(String id, int index, String key, Kind kind, String raw, Sexp sex,
                          String name, Sexp term, List<Sexp> assumLits, Sexp sort) {}

    public record ModelDef(String name, String sort, Sexp body, boolean nullary) {}

    public enum RKind { SAT, UNSAT, UNKNOWN, MODEL, CORE, VALUES, ERROR, OTHER }

    public record Response(String forCommandId, RKind kind, String raw,
                           List<ModelDef> defs, List<String> coreNames) {}

    public record Session(String logic, List<Command> commands, List<Response> responses,
                          Map<String, Response> byCommand, Map<String, String> modelForCheck,
                          Map<String, String> coreForCheck, List<String> diagnostics) {

        public Command command(String id) {
            for (Command c : commands) if (c.id().equals(id)) return c;
            return null;
        }

        public Response responseFor(String commandId) {
            return byCommand.get(commandId);
        }

        public Response modelResponseForCheck(String checkCommandId) {
            String gm = modelForCheck.get(checkCommandId);
            return gm == null ? null : byCommand.get(gm);
        }

        public Response coreResponseForCheck(String checkCommandId) {
            String gc = coreForCheck.get(checkCommandId);
            return gc == null ? null : byCommand.get(gc);
        }
    }

    public static final String SORT_INT = "Int";
    public static final String SORT_BOOL = "Bool";
    public static final String SORT_ARRAY_II = "(Array Int Int)";

    public static boolean supportedSort(String sort) {
        return SORT_INT.equals(sort) || SORT_BOOL.equals(sort) || SORT_ARRAY_II.equals(sort);
    }

    public static Session parse(String scriptText, String responsesText) {
        List<String> diagnostics = new ArrayList<>();
        List<Command> commands = new ArrayList<>();
        String logic = null;
        int pushOrd = 0, popOrd = 0, checkOrd = 0, gmOrd = 0, gcOrd = 0, gvOrd = 0, otherOrd = 0;
        String lastCheckId = null;
        Map<String, String> modelForCheck = new LinkedHashMap<>();
        Map<String, String> coreForCheck = new LinkedHashMap<>();

        for (Sexp.Span span : Sexp.parseAllWithSpans(scriptText)) {
            Sexp sex = span.sexp();
            String raw = scriptText.substring(span.start(), span.end());
            String id = String.format("c%03d", commands.size() + 1);
            int index = commands.size();
            Kind kind = Kind.OTHER;
            String name = null, key;
            Sexp term = null, sort = null;
            List<Sexp> assum = List.of();
            String head = sex.headAtom();
            if (head == null) {
                key = "other:#" + (++otherOrd);
                diagnostics.add(id + ": toplevel non-command form kept raw: " + raw);
            } else {
                switch (head) {
                    case "set-logic" -> {
                        kind = Kind.SET_LOGIC;
                        logic = sex.items().size() > 1 ? sex.items().get(1).atom() : null;
                        key = "logic";
                    }
                    case "set-info" -> {
                        kind = Kind.SET_INFO;
                        key = "info:" + (sex.items().size() > 1 ? sex.items().get(1).atom() : "#" + index);
                    }
                    case "declare-const", "declare-fun" -> {
                        kind = Kind.DECLARE;
                        name = sex.items().size() > 1 ? sex.items().get(1).atom() : null;
                        sort = sex.items().size() > 2 ? sex.items().get(sex.items().size() - 1) : null;
                        key = "decl:" + name;
                        String sortStr = sort == null ? "?" : Sexp.str(sort);
                        if (!supportedSort(sortStr)) {
                            diagnostics.add(id + ": declaration '" + name + "' uses unsupported sort/theory '"
                                    + sortStr + "'; kept raw, excluded from local evaluation");
                        }
                    }
                    case "assert" -> {
                        kind = Kind.ASSERT;
                        Sexp body = sex.items().size() > 1 ? sex.items().get(1) : null;
                        if (body != null && "!".equals(body.headAtom()) && body.items().size() >= 2) {
                            term = body.items().get(1);
                            List<Sexp> attrs = body.items();
                            for (int i = 2; i + 1 < attrs.size(); i++) {
                                if (":named".equals(attrs.get(i).atom())) name = attrs.get(i + 1).atom();
                            }
                        } else {
                            term = body;
                        }
                        key = name != null ? "assert:" + name : "assert:#" + index;
                        collectRealLiteralDiagnostics(term, id, diagnostics);
                    }
                    case "push" -> {
                        kind = Kind.PUSH;
                        key = "push:" + (++pushOrd);
                    }
                    case "pop" -> {
                        kind = Kind.POP;
                        key = "pop:" + (++popOrd);
                    }
                    case "check-sat" -> {
                        kind = Kind.CHECK_SAT;
                        key = "check:" + (++checkOrd);
                        lastCheckId = id;
                    }
                    case "check-sat-assuming" -> {
                        kind = Kind.CHECK_SAT_ASSUMING;
                        key = "check:" + (++checkOrd);
                        lastCheckId = id;
                        if (sex.items().size() > 1 && sex.items().get(1).items() != null) {
                            assum = sex.items().get(1).items();
                        }
                    }
                    case "get-model" -> {
                        kind = Kind.GET_MODEL;
                        key = "get-model:#" + (++gmOrd);
                        if (lastCheckId != null) modelForCheck.put(lastCheckId, id);
                        else diagnostics.add(id + ": get-model without preceding check-sat");
                    }
                    case "get-unsat-core" -> {
                        kind = Kind.GET_UNSAT_CORE;
                        key = "get-unsat-core:#" + (++gcOrd);
                        if (lastCheckId != null) coreForCheck.put(lastCheckId, id);
                        else diagnostics.add(id + ": get-unsat-core without preceding check-sat");
                    }
                    case "get-value" -> {
                        kind = Kind.GET_VALUE;
                        key = "get-value:#" + (++gvOrd);
                    }
                    default -> {
                        kind = Kind.OTHER;
                        key = "other:#" + (++otherOrd);
                    }
                }
            }
            commands.add(new Command(id, index, key, kind, raw, sex, name, term, assum, sort));
        }

        List<Response> responses = new ArrayList<>();
        Map<String, Response> byCommand = new LinkedHashMap<>();
        if (responsesText != null && !responsesText.isBlank()) {
            List<Sexp.Span> forms = Sexp.parseAllWithSpans(responsesText);
            int[] cursor = {0};
            for (Command c : commands) {
                RKind expect = switch (c.kind()) {
                    case CHECK_SAT, CHECK_SAT_ASSUMING -> RKind.SAT;
                    case GET_MODEL -> RKind.MODEL;
                    case GET_UNSAT_CORE -> RKind.CORE;
                    case GET_VALUE -> RKind.VALUES;
                    default -> null;
                };
                if (expect == null) continue;
                if (cursor[0] >= forms.size()) {
                    diagnostics.add(c.id() + ": no solver response supplied for " + c.kind());
                    continue;
                }
                Sexp.Span form = forms.get(cursor[0]++);
                String raw = responsesText.substring(form.start(), form.end());
                Response r = classify(c, form.sexp(), raw, expect, diagnostics);
                responses.add(r);
                byCommand.put(c.id(), r);
            }
            if (cursor[0] < forms.size()) {
                diagnostics.add("unassociated solver responses kept raw: " + (forms.size() - cursor[0]) + " form(s)");
            }
        }
        return new Session(logic, commands, responses, byCommand, modelForCheck, coreForCheck, diagnostics);
    }

    private static Response classify(Command c, Sexp sex, String raw, RKind expect, List<String> diagnostics) {
        RKind kind;
        List<ModelDef> defs = List.of();
        List<String> core = List.of();
        String a = sex.atom();
        if ("sat".equals(a)) kind = RKind.SAT;
        else if ("unsat".equals(a)) kind = RKind.UNSAT;
        else if ("unknown".equals(a)) kind = RKind.UNKNOWN;
        else if ("error".equals(a) || "error".equals(sex.headAtom())) kind = RKind.ERROR;
        else if ("model".equals(sex.headAtom())) {
            kind = RKind.MODEL;
            defs = parseDefs(sex.items().subList(1, sex.items().size()));
        } else if ("define-fun".equals(sex.headAtom())) {
            kind = RKind.MODEL;
            defs = parseDefs(List.of(sex));
        } else if (sex.items() != null && !sex.items().isEmpty() && sex.items().stream().allMatch(Sexp::isAtom)) {
            kind = RKind.CORE;
            core = sex.items().stream().map(Sexp::atom).toList();
        } else if (sex.items() != null) {
            kind = RKind.VALUES;
        } else {
            kind = RKind.OTHER;
        }
        boolean ok = switch (expect) {
            case SAT -> kind == RKind.SAT || kind == RKind.UNSAT || kind == RKind.UNKNOWN || kind == RKind.ERROR;
            case MODEL -> kind == RKind.MODEL || kind == RKind.ERROR;
            case CORE -> kind == RKind.CORE || kind == RKind.ERROR;
            default -> true;
        };
        if (!ok) {
            diagnostics.add(c.id() + ": response kind " + kind + " does not match " + c.kind()
                    + "; kept raw in declared order");
        }
        return new Response(c.id(), kind, raw, defs, core);
    }

    private static List<ModelDef> parseDefs(List<Sexp> forms) {
        List<ModelDef> out = new ArrayList<>();
        for (Sexp f : forms) {
            if (!"define-fun".equals(f.headAtom()) || f.items().size() < 5) continue;
            String name = f.items().get(1).atom();
            Sexp args = f.items().get(2);
            String sort = Sexp.str(f.items().get(3));
            Sexp body = f.items().get(4);
            out.add(new ModelDef(name, sort, body, args.items() != null && args.items().isEmpty()));
        }
        return out;
    }

    private static void collectRealLiteralDiagnostics(Sexp term, String cmdId, List<String> diagnostics) {
        if (term == null) return;
        if (term.isAtom()) {
            String a = term.atom();
            if (a != null && a.matches("\\d+\\.\\d+")) {
                diagnostics.add(cmdId + ": value '" + a + "' cannot be normalized into the Int domain;"
                        + " kept raw at its original position with this diagnostic");
            }
            return;
        }
        for (Sexp x : term.items()) collectRealLiteralDiagnostics(x, cmdId, diagnostics);
    }
}
