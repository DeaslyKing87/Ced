package com.ced.sharplan;

/** Transport acknowledgements and renderer state never prove a physical TV key. */
public final class TvControlGate {
    private TvControlGate() {}
    public static boolean canUnlock(boolean dlnaRenderer, boolean identifiedTvKey,
                                    boolean physicalTvEffectConfirmed) {
        return !dlnaRenderer && identifiedTvKey && physicalTvEffectConfirmed;
    }
}
