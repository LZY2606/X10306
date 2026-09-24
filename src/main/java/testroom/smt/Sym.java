package testroom.smt;

/**
 * 带原始表示与单位/坐标基准的符号。raw 是会话里逐字符保留的原文，
 * normalized 仅在派生层生成（见 {@link testroom.domain.UnitRef}）。
 */
public record Sym(String name, String raw) {
    public Sym(String name) { this(name, name); }
    public String toString() { return raw; }
}
