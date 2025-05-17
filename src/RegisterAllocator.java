import java.util.*;

public class RegisterAllocator {
    private static class LiveInterval {
        final String varName;
        final int start;
        final int end;
        String register;
        String spillLocation;

        LiveInterval(String varName, int start, int end) {
            this.varName = varName;
            this.start = start;
            this.end = end;
        }
    }

    private final List<String> availableRegisters = new ArrayList<>();
    private final Map<String, LiveInterval> intervals = new HashMap<>();
    private final PriorityQueue<LiveInterval> active = new PriorityQueue<>(
        Comparator.comparingInt((LiveInterval i) -> i.end)
            .thenComparing(i -> i.varName)  // 二级排序确保稳定性
    );
    private int stackOffset = 0;

    public RegisterAllocator() {
        // 初始化寄存器列表 (t0-t6, s0-s11)
        for (int i = 0; i < 4; i++)
            availableRegisters.add("t" + i);
        for (int i = 0; i < 12; i++)
            availableRegisters.add("s" + i);
        for (int i = 1; i < 7; i++)
            availableRegisters.add("a" + i);
    }

    public void addInterval(String varName, int start, int end) {
        intervals.put(varName, new LiveInterval(varName, start, end));
    }

    public void allocate() {
        List<LiveInterval> sorted = new ArrayList<>(intervals.values());
        sorted.sort(Comparator.comparingInt(i -> i.start));

        for (LiveInterval current : sorted) {
            expireOldIntervals(current.start);

            if (availableRegisters.isEmpty()) {
                // 获取最早结束的活跃区间
                LiveInterval spill = active.peek();

                if (spill.end > current.end) {
                    // 溢出当前区间
                    current.register = spill.register;
                    spillCurrent(current);
                } else {
                    // 溢出已存在的区间
                    active.poll();
                    spillInterval(spill);
                    assignRegister(current);
                    active.offer(current);
                }
            } else {
                assignRegister(current);
                active.offer(current);
            }
        }
    }

    private void expireOldIntervals(int position) {
        while (!active.isEmpty()) {
            LiveInterval interval = active.peek();
            if (interval.end > position) {  // 严格大于时停止
                break;
            }
            active.poll();
            availableRegisters.add(interval.register);
        }
    }

    private void assignRegister(LiveInterval interval) {
        interval.register = availableRegisters.remove(0);
    }

    private void spillCurrent(LiveInterval interval) {
        interval.spillLocation = "" + stackOffset;
        stackOffset += 4;
    }

    private void spillInterval(LiveInterval interval) {
        interval.spillLocation = "" + stackOffset;
        stackOffset += 4;
        availableRegisters.add(interval.register);
        interval.register = null;
    }

    // 以下为访问方法
    public String getRegister(String varName) {
        LiveInterval interval = intervals.get(varName);
        return interval != null ? interval.register : null;
    }

    public String getSpillLocation(String varName) {
        LiveInterval interval = intervals.get(varName);
        return interval != null ? interval.spillLocation : null;
    }

    public int getStackSize() {
        return stackOffset;
    }
}
