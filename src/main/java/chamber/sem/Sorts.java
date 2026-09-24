package chamber.sem;

import chamber.sexpr.Sexpr;

/** Sort support classification for the finite semantic subset. */
public final class Sorts {

    private Sorts() {}

    /** Returns null when the sort is inside the local finite subset,
     *  otherwise the name of the unsupported theory. */
    public static String unsupportedTheory(Sexpr sort) {
        if (sort.isAtom()) {
            return switch (sort.asAtom()) {
                case "Bool", "Int", "Real" -> null;
                case "String", "Char", "RegLan" -> "Strings";
                default -> sort.asAtom().startsWith("Float") || sort.asAtom().startsWith("RoundingMode")
                        ? "FloatingPoint" : "unknown-sort:" + sort.asAtom();
            };
        }
        var items = sort.asList();
        if (items.size() == 3 && items.get(0).isAtom() && items.get(0).asAtom().equals("Array")) {
            String idx = unsupportedTheory(items.get(1));
            String elem = unsupportedTheory(items.get(2));
            if (idx != null) return idx;
            return elem;
        }
        if (items.size() == 2 && items.get(0).isAtom() && items.get(0).asAtom().equals("_")
                && items.get(1).isAtom() && items.get(1).asAtom().equals("BitVec")) {
            return "BitVector";
        }
        if (!items.isEmpty() && items.get(0).isAtom() && items.get(0).asAtom().equals("_")) {
            return "parameterised:" + (items.size() > 1 ? items.get(1) : "?");
        }
        return "unknown-sort:" + sort;
    }
}
