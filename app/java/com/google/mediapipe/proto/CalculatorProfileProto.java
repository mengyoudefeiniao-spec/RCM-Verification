package com.google.mediapipe.proto;

/**
 * 占位实现：理由同 GraphTemplateProto。仅 GraphProfiler.getCalculatorProfiles()
 * 会用到，本 App 不启用 profiling。
 */
public final class CalculatorProfileProto {

    private CalculatorProfileProto() {
    }

    public static final class CalculatorProfile {
        public CalculatorProfile() {
        }

        public static CalculatorProfile parseFrom(byte[] data) {
            return new CalculatorProfile();
        }

        public byte[] toByteArray() {
            return new byte[0];
        }

        public int getSerializedSize() {
            return 0;
        }
    }
}
