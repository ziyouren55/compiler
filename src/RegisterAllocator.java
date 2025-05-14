import java.util.*;
import org.llvm4j.llvm4j.Value;

public class RegisterAllocator {
    private final List<String> availableRegisters;
    private final Map<String, String> variableToRegister;
    private final Stack<String> savedRegisters;
    private final Map<String, LiveRange> liveRanges; // 记录变量的活跃区间
    private final Map<String, Integer> lastUse; // 记录变量的最后使用位置

    // 活跃区间类
    private static class LiveRange {
        int start; // 开始位置
        int end; // 结束位置
        String reg; // 分配的寄存器

        LiveRange(int start) {
            this.start = start;
            this.end = start;
            this.reg = null;
        }
    }

    public RegisterAllocator() {
        // 初始化可用寄存器列表（t0-t6, s0-s11）
        this.availableRegisters = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            availableRegisters.add("t" + i);
        }
        for (int i = 0; i < 12; i++) {
            availableRegisters.add("s" + i);
        }

        this.variableToRegister = new HashMap<>();
        this.savedRegisters = new Stack<>();
        this.liveRanges = new HashMap<>();
        this.lastUse = new HashMap<>();
    }

    // 记录变量的定义位置
    public void defineVariable(String varName, int position) {
        if (!liveRanges.containsKey(varName)) {
            liveRanges.put(varName, new LiveRange(position));
        }
    }

    // 记录变量的使用位置
    public void useVariable(String varName, int position) {
        LiveRange range = liveRanges.get(varName);
        if (range != null) {
            range.end = position;
        }
        lastUse.put(varName, position);
    }

    // 分配寄存器
    public String allocateRegister(String varName, int currentPosition) {
        // 如果变量已经有分配的寄存器，直接返回
        String existingReg = variableToRegister.get(varName);
        if (existingReg != null) {
            return existingReg;
        }

        // 如果有可用寄存器，直接分配
        if (!availableRegisters.isEmpty()) {
            String reg = availableRegisters.remove(0);
            variableToRegister.put(varName, reg);
            savedRegisters.push(reg);
            return reg;
        }

        // 如果没有可用寄存器，需要选择一个寄存器进行溢出
        String regToSpill = findRegisterToSpill(currentPosition);
        if (regToSpill != null) {
            // 找到使用这个寄存器的变量
            String spilledVar = null;
            for (Map.Entry<String, String> entry : variableToRegister.entrySet()) {
                if (entry.getValue().equals(regToSpill)) {
                    spilledVar = entry.getKey();
                    break;
                }
            }

            // 溢出变量
            if (spilledVar != null) {
                variableToRegister.remove(spilledVar);
                // 这里应该生成溢出代码，将寄存器内容保存到栈上
                // TODO: 生成溢出代码
            }

            // 分配寄存器给新变量
            variableToRegister.put(varName, regToSpill);
            return regToSpill;
        }

        throw new RuntimeException("No available registers and cannot spill");
    }

    // 查找可以溢出的寄存器
    private String findRegisterToSpill(int currentPosition) {
        String bestReg = null;
        int furthestUse = -1;

        // 遍历所有已分配的寄存器
        for (String reg : savedRegisters) {
            // 找到使用这个寄存器的变量
            String varName = null;
            for (Map.Entry<String, String> entry : variableToRegister.entrySet()) {
                if (entry.getValue().equals(reg)) {
                    varName = entry.getKey();
                    break;
                }
            }

            if (varName != null) {
                // 获取变量的最后使用位置
                Integer lastUsePos = lastUse.get(varName);
                if (lastUsePos != null && lastUsePos > furthestUse) {
                    furthestUse = lastUsePos;
                    bestReg = reg;
                }
            }
        }

        return bestReg;
    }

    public void freeRegister(String reg) {
        if (!variableToRegister.containsValue(reg)) {
            // 将释放的寄存器放回可用列表的开头
            availableRegisters.add(0, reg);
            savedRegisters.remove(reg);
        }
    }

    public void mapVariableToRegister(String varName, String reg) {
        variableToRegister.put(varName, reg);
    }

    public String getRegisterForVariable(String varName) {
        return variableToRegister.get(varName);
    }

    public int getStackSize() {
        return savedRegisters.size() * 4; // 每个寄存器占用4字节
    }

}
