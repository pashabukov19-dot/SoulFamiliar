package com.soulfamiliar;

import java.util.concurrent.ThreadLocalRandom;

public enum FamiliarMood {
    DEVOTED("преданное и предупредительное"),
    PHILOSOPHICAL("мрачно-философское"),
    MISCHIEVOUS("азартно-игривое"),
    MELANCHOLIC("меланхолично-поэтичное");

    private final String russianLabel;

    FamiliarMood(String russianLabel) {
        this.russianLabel = russianLabel;
    }

    public String getRussianLabel() {
        return russianLabel;
    }

    public static FamiliarMood randomMood() {
        FamiliarMood[] values = values();
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }

    public FamiliarMood nextDifferentMood() {
        FamiliarMood[] values = values();
        int idx = (this.ordinal() + 1 + ThreadLocalRandom.current().nextInt(values.length - 1)) % values.length;
        return values[idx];
    }
}
