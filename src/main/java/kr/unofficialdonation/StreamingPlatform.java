package kr.unofficialdonation;

import java.util.Locale;

/** Platform identifier stored with each player binding. */
enum StreamingPlatform {
    CHZZK("치지직"), CIME("씨미"), SOOP("숲");

    private final String koreanName;

    StreamingPlatform(String koreanName) { this.koreanName = koreanName; }
    String koreanName() { return koreanName; }

    static StreamingPlatform parse(String raw) {
        if (raw == null || raw.isBlank()) return CHZZK;
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "CHZZK", "치지직" -> CHZZK;
            case "CIME", "CI.ME", "씨미" -> CIME;
            case "SOOP", "숲", "AFREECA", "AFREECATV" -> SOOP;
            default -> null;
        };
    }
}
