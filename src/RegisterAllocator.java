import java.util.*;
import org.llvm4j.llvm4j.Value;

public class RegisterAllocator {
    private final List<String> availableRegisters;
    private final Map<String, String> variableToRegister;
    private final Stack<String> savedRegisters;

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
    }

    public String allocateRegister() {
        if (availableRegisters.isEmpty()) {
            throw new RuntimeException("No available registers");
        }
        String reg = availableRegisters.remove(0);
        savedRegisters.push(reg);
        return reg;
    }

    public void freeRegister(String reg) {
        if (!variableToRegister.containsValue(reg)) {
            availableRegisters.add(reg);
            savedRegisters.remove(reg);
        }
    }

    public void mapVariableToRegister(String varName, String reg) {
        variableToRegister.put(varName, reg);
    }

    public String getRegisterForVariable(String varName) {
        return variableToRegister.get(varName);
    }

    public void saveRegisters() {
        for (String reg : savedRegisters) {
            // 生成保存寄存器的汇编代码
            System.out.printf("    sw %s, -4(sp)\n", reg);
        }
    }

    public void restoreRegisters() {
        for (int i = savedRegisters.size() - 1; i >= 0; i--) {
            String reg = savedRegisters.get(i);
            // 生成恢复寄存器的汇编代码
            System.out.printf("    lw %s, -4(sp)\n", reg);
        }
    }

    public int getStackSize() {
        return savedRegisters.size() * 4; // 每个寄存器占用4字节
    }

    // 为变量分配位置（寄存器或栈空间）
    public String allocate(Value value) {
        // Implementation needed
        throw new UnsupportedOperationException("Method not implemented");
    }

    // 释放变量占用的资源
    public void free(Value value) {
        // Implementation needed
        throw new UnsupportedOperationException("Method not implemented");
    }
}