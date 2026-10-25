package kr.unofficialdonation;

import java.util.Locale;

enum DonationType {
    CHAT("채팅후원"), VIDEO("영상후원"), MISSION("미션후원"), SUBSCRIPTION("구독후원"), PARTY("파티후원"), FOLLOW("팔로우");

    private final String koreanName;
    DonationType(String koreanName) { this.koreanName = koreanName; }
    String koreanName() { return koreanName; }

    static DonationType parse(String value) {
        String normalized = value.toUpperCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (normalized) {
            case "CHAT", "채팅", "채팅후원" -> CHAT;
            case "VIDEO", "영상", "영상후원" -> VIDEO;
            case "MISSION", "미션", "미션후원" -> MISSION;
            case "SUBSCRIPTION", "SUBSCRIBE", "구독", "구독후원" -> SUBSCRIPTION;
            case "PARTY", "MISSIONPARTICIPATION", "PARTICIPATION", "파티", "파티후원" -> PARTY;
            case "FOLLOW", "팔로우" -> FOLLOW;
            default -> null;
        };
    }
}
