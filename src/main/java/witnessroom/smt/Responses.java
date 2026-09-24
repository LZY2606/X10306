package witnessroom.smt;

import java.util.ArrayList;
import java.util.List;

/** Ordered solver responses (sat / unsat / unknown), one per check command. */
public final class Responses {
    public record Response(int index, String verdict, String raw, int line) {}

    public static List<Response> parse(String src) {
        List<Response> out = new ArrayList<>();
        String[] lines = src.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty() || t.startsWith(";")) continue;
            String verdict = switch (t) {
                case "sat", "unsat", "unknown" -> t;
                default -> "unrecognized";
            };
            out.add(new Response(out.size(), verdict, t, i + 1));
        }
        return out;
    }
}
