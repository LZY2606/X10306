package smtwitness.bundle;

import smtwitness.smt.Block;

import java.util.List;

/**
 * Offline "smt-testimony/v1" bundle. The transcript keeps the exact command stream,
 * block order, response blocks and logic declarations (set-logic is a command block).
 * quantities carries domain units/coordinate bases and finite domain metadata.
 */
public record Bundle(
        String format,
        String version,
        String name,
        String scenario,
        String description,
        List<Block> blocks,
        List<Quantity> quantities
) {
    public static final String FORMAT = "smt-testimony";
    public static final String VERSION = "1";

    public Bundle {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        quantities = quantities == null ? List.of() : List.copyOf(quantities);
    }

    /** Domain quantity metadata: raw representation is preserved; normalization is a derived layer. */
    public record Quantity(
            String symbol,
            String rawValue,
            String rawUnit,
            String basis,
            Long domainLo,
            Long domainHi,
            String note
    ) {}
}
