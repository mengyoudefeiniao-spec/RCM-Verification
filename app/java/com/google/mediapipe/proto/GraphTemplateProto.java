package com.google.mediapipe.proto;

/**
 * 占位实现：MediaPipe 0.10.21 的 AAR 未包含这两个 protobuf 生成类，
 * 但 Graph 的 loadBinaryGraphTemplate(CalculatorGraphTemplate) 签名引用了它，
 * 签名解析会强制要求该类存在（否则 NoClassDefFoundError）。
 * 本 App 从不调用 template / profiling 路径，故提供最小实现即可。
 */
public final class GraphTemplateProto {

    private GraphTemplateProto() {
    }

    public static final class CalculatorGraphTemplate {
        public CalculatorGraphTemplate() {
        }

        public byte[] toByteArray() {
            return new byte[0];
        }

        public int getSerializedSize() {
            return 0;
        }
    }
}
