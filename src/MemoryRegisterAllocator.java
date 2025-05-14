import java.util.*;

public class MemoryRegisterAllocator {
    private final Map<String, Integer> variableOffsets;
    private int currentOffset;

    public MemoryRegisterAllocator() {
        this.variableOffsets = new HashMap<>();
        this.currentOffset = 0;
    }

    public int allocateVariable(String varName) {
        int offset = currentOffset;
        variableOffsets.put(varName, offset);
        currentOffset += 4; // 每个变量分配4字节
        return offset;
    }

    public int getVariableOffset(String varName) {
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
        int offset = getVariableOffset(varName);
        return String.format("    lw %s, %d(sp)\n", reg, offset);
    }

    public String getStoreInstruction(String reg, String varName) {
        int offset = getVariableOffset(varName);
        return String.format("    sw %s, %d(sp)\n", reg, offset);
    }
}