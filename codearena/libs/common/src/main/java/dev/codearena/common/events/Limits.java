package dev.codearena.common.events;

/** Resource limits for one run of a problem's test set. */
public record Limits(int timeMs, int memoryMb) {
    public static final Limits DEFAULT = new Limits(2000, 256);
}