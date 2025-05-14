import java.util.*;

public class MemoryRegisterAllocator {
    private final Map<String, Integer> variableOffsets;
    private final Map<String, String> globalVariables;
    private int currentOffset;

    public MemoryRegisterAllocator() {
        this.variableOffsets = new HashMap<>();
        this.globalVariables = new HashMap<>();
        this.currentOffset = 0;
    }

    public void addGlobalVariable(String varName) {
        globalVariables.put(varName, varName);
    }

    public boolean isGlobalVariable(String varName) {
        return globalVariables.containsKey(varName);
    }

    public String getGlobalVariableLabel(String varName) {
        return globalVariables.get(varName);
    }

    public int allocateVariable(String varName) {
        int offset = currentOffset;
        variableOffsets.put(varName, offset);
        currentOffset += 4; // 每个变量分配4字节
        return offset;
    }

    public int getVariableOffset(String varName) {
        if (isGlobalVariable(varName)) {
            throw new IllegalArgumentException("Variable " + varName + " is a global variable");
        }

        Integer offset = variableOffsets.get(varName);
        if (offset == null) {
            throw new IllegalArgumentException("Variable " + varName + " not found");
        }
        return offset;
    }

    public void reset() {
        variableOffsets.clear();
        currentOffset = 0;
    }

    public int getTotalSize() {
        return currentOffset;
    }

    public String getStackPointerAdjustment() {
        return String.format("    addi sp, sp, -%d\n", getTotalSize());
    }

    public String getStackPointerRestore() {
        return String.format("    addi sp, sp, %d\n", getTotalSize());
    }

    public String getLoadInstruction(String varName, String reg) {
        if (isGlobalVariable(varName)) {
            return String.format("    la %s, %s\n    lw %s, 0(%s)\n",
                    reg, getGlobalVariableLabel(varName), reg, reg);
        } else {
            int offset = getVariableOffset(varName);
            return String.format("    lw %s, %d(sp)\n", reg, offset);
        }
    }

    public String getStoreInstruction(String reg, String varName) {
        if (isGlobalVariable(varName)) {
            return String.format("    la t6, %s\n    sw %s, 0(t6)\n",
                    getGlobalVariableLabel(varName), reg);
        } else {
            int offset = getVariableOffset(varName);
            return String.format("    sw %s, %d(sp)\n", reg, offset);
        }
    }
}