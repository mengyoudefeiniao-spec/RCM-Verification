package iq.digitalration.faceverify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 基于 MediaPipe Face Mesh blendshape 的活体挑战-响应状态机。
 * 随机顺序 + 3 个不同动作，静态照片无法在时序上完成眨眼/转头。
 *
 * 为便于在 JVM 上单测，输入抽象成 {@link Sensor}，不依赖 Android。
 */
public final class Liveness {

    public enum Task { BLINK, TURN, MOUTH, SMILE }

    /** 一帧的活体观测量。 */
    public interface Sensor {
        boolean found();
        float blink();    // 0..1，双眼闭合程度均值
        float yaw();      // 头部水平转动代理量，0 = 正脸
        float jawOpen();  // 0..1
        float smile();    // 0..1
    }

    public static final class State {
        public String instruction = "";
        public String hint = "";
        public int done = 0;
        public int total = 3;
        public boolean finished = false;
        public boolean passed = false;
        /** 全程峰值（每帧都累计，含自然微动）：只用来看数据，**不是**动作判据。 */
        public float maxBlink = 0, maxYaw = 0, maxJaw = 0, maxSmile = 0;
        public List<String> log = new ArrayList<>();

        /** 本局实际抽到的动作（超时被替换后按替换结果记）。 */
        public final List<Task> tasks = new ArrayList<>();
        /** 各动作在"它进行期间"的峰值：没抽到的动作恒为 0，不会把别处的自然微动算进来。 */
        public float blinkPeak = 0, turnPeak = 0, jawPeak = 0, smilePeak = 0;

        /** 抽到的动作序列，例如「眨眼 → 张嘴 → 微笑」。 */
        public String tasksText() {
            StringBuilder sb = new StringBuilder();
            for (Task t : tasks) {
                if (sb.length() > 0) sb.append(" → ");
                sb.append(labelFor(t));
            }
            return sb.toString();
        }

        /**
         * 只列**抽到的**动作及其达成峰值。
         *
         * <p>旧界面把全程 {@code maxYaw} 当"转头"显示，没抽到转头也会出现 0.04 这种自然微动，
         * 看起来像"没让转头却判了转头"。现在没抽到的动作不出现；全程峰值单独标注。
         */
        public String peaksText() {
            StringBuilder sb = new StringBuilder();
            for (Task t : tasks) {
                if (sb.length() > 0) sb.append(" · ");
                switch (t) {
                    case BLINK: sb.append(String.format(Locale.US, "眨眼%.2f", blinkPeak)); break;
                    case TURN:  sb.append(String.format(Locale.US, "转头%.2f", turnPeak)); break;
                    case MOUTH: sb.append(String.format(Locale.US, "张嘴%.2f", jawPeak)); break;
                    case SMILE: sb.append(String.format(Locale.US, "微笑%.2f", smilePeak)); break;
                    default: break;
                }
            }
            return sb.toString();
        }
    }

    private final List<Task> seq = new ArrayList<>();
    private final State st = new State();
    private int idx = 0;
    private int hold = 0;
    private long taskStart;
    private float prevBlink = 0f;
    private boolean blinkArmed = true;
    private int timeouts = 0;          // 当前动作连续超时次数

    private static final float BLINK_ON = 0.45f;
    private static final float BLINK_OFF = 0.25f;
    private static final float YAW_TURN = 0.20f;
    private static final float JAW_OPEN = 0.42f;
    private static final float SMILE_ON = 0.52f;
    private static final long TASK_TIMEOUT_MS = 12000;

    public Liveness(long seed) {
        List<Task> pool = new ArrayList<>();
        pool.add(Task.BLINK);
        pool.add(Task.TURN);
        pool.add(Task.MOUTH);
        pool.add(Task.SMILE);
        Collections.shuffle(pool, new Random(seed));
        for (int i = 0; i < 3; i++) seq.add(pool.get(i));
        st.tasks.addAll(seq);
        st.total = 3;
        st.instruction = instructionFor(seq.get(0));
        taskStart = System.currentTimeMillis();
    }

    public State state() { return st; }

    public State update(Sensor s, long ts) {
        if (s == null || !s.found()) {
            st.hint = "未检测到人脸，请把脸放到框内";
            return st;
        }
        if (st.finished) return st;

        float blink = s.blink();
        st.maxBlink = Math.max(st.maxBlink, blink);
        st.maxYaw = Math.max(st.maxYaw, Math.abs(s.yaw()));
        st.maxJaw = Math.max(st.maxJaw, s.jawOpen());
        st.maxSmile = Math.max(st.maxSmile, s.smile());

        Task t = seq.get(idx);
        // 只在该动作**进行期间**累计它自己的峰值（没抽到的动作不会沾到别处的微动）
        switch (t) {
            case BLINK: st.blinkPeak = Math.max(st.blinkPeak, blink); break;
            case TURN:  st.turnPeak = Math.max(st.turnPeak, Math.abs(s.yaw())); break;
            case MOUTH: st.jawPeak = Math.max(st.jawPeak, s.jawOpen()); break;
            case SMILE: st.smilePeak = Math.max(st.smilePeak, s.smile()); break;
        }
        boolean ok = false;
        switch (t) {
            case BLINK: {
                if (blinkArmed && blink >= BLINK_ON) {
                    blinkArmed = false;
                    ok = true;
                } else if (!blinkArmed && blink <= BLINK_OFF) {
                    blinkArmed = true;
                }
                break;
            }
            case TURN: {
                if (Math.abs(s.yaw()) >= YAW_TURN) hold++;
                else hold = 0;
                ok = hold >= 2;
                break;
            }
            case MOUTH: {
                if (s.jawOpen() >= JAW_OPEN) hold++;
                else hold = 0;
                ok = hold >= 2;
                break;
            }
            case SMILE: {
                if (s.smile() >= SMILE_ON) hold++;
                else hold = 0;
                ok = hold >= 2;
                break;
            }
        }
        prevBlink = blink;

        if (ok) {
            timeouts = 0;
            st.done++;
            st.log.add(t.name().toLowerCase() + "@" + ts);
            idx++;
            hold = 0;
            if (idx >= seq.size()) {
                st.finished = true;
                st.passed = true;
                st.instruction = "活体检测通过";
                st.hint = "";
            } else {
                st.instruction = instructionFor(seq.get(idx));
                st.hint = "";
                taskStart = ts;
            }
        } else if (ts - taskStart > TASK_TIMEOUT_MS) {
            timeouts++;
            hold = 0;
            if (t == Task.BLINK) blinkArmed = true;
            if (timeouts >= 2) {
                // 同一动作连续两次都没识别到，说明这台设备/这个人做不了，换一个等价动作，
                // 否则用户会被永久卡住（活体强度不降：换的仍是需要真实运动的动作）。
                Task alt = pickAlternative(t);
                if (alt != null) {
                    seq.set(idx, alt);
                    st.tasks.set(idx, alt);
                    st.log.add("swap:" + t.name().toLowerCase() + "->" + alt.name().toLowerCase());
                    st.hint = "换一个动作：" + instructionFor(alt);
                    taskStart = ts;
                    timeouts = 0;
                    return st;
                }
            }
            st.hint = "动作没识别到，请再试一次：" + instructionFor(t);
            taskStart = ts;
        } else {
            st.hint = hintFor(t, s);
        }
        return st;
    }

    /** 当前待完成的动作（给测试/UI 用，避免用提示文案去匹配）。 */
    public Task currentTask() {
        return idx < seq.size() ? seq.get(idx) : null;
    }

    /** 换一个尚未出现在序列里的动作；没有可选则返回 null。 */
    private Task pickAlternative(Task bad) {
        Task[] pool = {Task.MOUTH, Task.SMILE, Task.TURN, Task.BLINK};
        for (Task cand : pool) {
            if (cand == bad) continue;
            boolean used = false;
            for (int i = 0; i < seq.size(); i++) {
                if (i != idx && seq.get(i) == cand) { used = true; break; }
            }
            if (!used) return cand;
        }
        return null;
    }

    public String currentInstruction() {
        return idx < seq.size() ? instructionFor(seq.get(idx)) : "完成";
    }

    public static String instructionFor(Task t) {
        switch (t) {
            case BLINK: return "请眨一下眼睛";
            case TURN:  return "请把头转向一侧";
            case MOUTH: return "请张开嘴巴";
            case SMILE: return "请微笑";
            default: return "";
        }
    }

    /** 动作的短名（报告/结果页用）。 */
    public static String labelFor(Task t) {
        switch (t) {
            case BLINK: return "眨眼";
            case TURN:  return "转头";
            case MOUTH: return "张嘴";
            case SMILE: return "微笑";
            default: return "";
        }
    }

    private static String hintFor(Task t, Sensor s) {
        switch (t) {
            case BLINK: return "眼睛睁开 → 闭上 → 再睁开";
            case TURN:  return s.yaw() > 0 ? "再往右转一点" : "往任意一侧转头即可";
            case MOUTH: return "嘴巴再张大一点";
            case SMILE: return "嘴角上扬，笑一下";
            default: return "";
        }
    }
}
