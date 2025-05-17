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

    public int getCurrentOffset() {
        return currentOffset;
    }

}
