import org.bytedeco.llvm.LLVM.*;
import java.util.*;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * 未使用变量消除优化器类
 * 识别并消除只定义而未被使用的局部变量以及死存储
 */
public class UnusedVarEliminationOptimizer {

    private LLVMModuleRef module;
    private Set<String> globalVariables = new HashSet<>();

    /**
     * 构造函数
     *
     * @param module LLVM模块
     */
    public UnusedVarEliminationOptimizer(LLVMModuleRef module) {
        this.module = module;
        collectGlobalVariables();
    }

    /**
     * 收集模块中的所有全局变量
     */
    private void collectGlobalVariables() {
        globalVariables.clear();
        for (LLVMValueRef global = LLVMGetFirstGlobal(module); global != null; global = LLVMGetNextGlobal(global)) {
            String name = LLVMGetValueName(global).getString();
            globalVariables.add(name);
        }
    }

    /**
     * 运行未使用变量消除优化
     *
     * @return 是否进行了优化
     */
    public boolean run() {
        boolean changed = false;

        // 对每个函数进行未使用变量消除
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                changed = eliminateUnusedVars(func) || changed;
                changed = eliminateDeadStores(func) || changed;
            }
        }

        return changed;
    }

    /**
     * 在单个函数中消除未使用变量
     *
     * @param func 待优化的函数
     * @return 是否进行了优化
     */
    private boolean eliminateUnusedVars(LLVMValueRef func) {
        // 收集所有分配的变量
        Map<String, LLVMValueRef> allocaInsts = new HashMap<>();
        // 跟踪变量的使用情况
        Set<String> usedVars = new HashSet<>();
        // 需要删除的指令列表
        List<LLVMValueRef> toRemove = new ArrayList<>();

        // 第一阶段：收集所有局部变量和使用情况
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                int opcode = LLVMGetInstructionOpcode(inst);

                if (opcode == LLVMAlloca) {
                    // 记录分配指令
                    String varName = LLVMGetValueName(inst).getString();
                    allocaInsts.put(varName, inst);
                } else if (opcode == LLVMLoad) {
                    // 记录变量的读取使用
                    LLVMValueRef ptr = LLVMGetOperand(inst, 0); // load指令的源地址
                    String ptrName = LLVMGetValueName(ptr).getString();

                    // 如果是局部变量而非全局变量，标记为已使用
                    if (!globalVariables.contains(ptrName) && allocaInsts.containsKey(ptrName)) {
                        usedVars.add(ptrName);
                    }
                }
            }
        }

        // 第二阶段：识别未使用变量，标记要删除的指令
        for (Map.Entry<String, LLVMValueRef> entry : allocaInsts.entrySet()) {
            String varName = entry.getKey();
            LLVMValueRef allocaInst = entry.getValue();

            if (!usedVars.contains(varName)) {
                // 找到未使用的变量，准备删除其分配指令
                toRemove.add(allocaInst);
            }
        }

        // 第三阶段：找到使用未使用变量的store指令，也标记为要删除
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                int opcode = LLVMGetInstructionOpcode(inst);

                if (opcode == LLVMStore) {
                    LLVMValueRef ptr = LLVMGetOperand(inst, 1); // store指令的目标地址
                    String ptrName = LLVMGetValueName(ptr).getString();

                    // 如果store到未使用的变量，也标记此指令为删除
                    if (!usedVars.contains(ptrName) && allocaInsts.containsKey(ptrName)) {
                        toRemove.add(inst);
                    }
                }
            }
        }

        // 第四阶段：删除标记的指令
        for (LLVMValueRef inst : toRemove) {
            LLVMInstructionEraseFromParent(inst);
        }

        return !toRemove.isEmpty();
    }

    /**
     * 消除死存储 - 变量被写入但值从未被读取
     *
     * @param func 待优化的函数
     * @return 是否进行了优化
     */
    private boolean eliminateDeadStores(LLVMValueRef func) {
        // 收集所有分配的变量
        Map<String, LLVMValueRef> allocaInsts = new HashMap<>();

        // 跟踪每个变量的最后一次存储和所有加载
        Map<String, LLVMValueRef> lastStores = new HashMap<>();
        Map<String, Set<LLVMValueRef>> loads = new HashMap<>();

        // 记录变量的活跃性 - 是否影响到函数的输出
        Set<String> liveVars = new HashSet<>();

        // 需要删除的指令列表
        List<LLVMValueRef> toRemove = new ArrayList<>();

        // 第一阶段：收集所有局部变量、store和load指令
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                int opcode = LLVMGetInstructionOpcode(inst);

                if (opcode == LLVMAlloca) {
                    String varName = LLVMGetValueName(inst).getString();
                    allocaInsts.put(varName, inst);
                    loads.put(varName, new HashSet<>());
                } else if (opcode == LLVMStore) {
                    LLVMValueRef ptr = LLVMGetOperand(inst, 1); // store目标地址
                    String ptrName = LLVMGetValueName(ptr).getString();

                    if (!globalVariables.contains(ptrName) && allocaInsts.containsKey(ptrName)) {
                        lastStores.put(ptrName, inst);
                    }
                } else if (opcode == LLVMLoad) {
                    LLVMValueRef ptr = LLVMGetOperand(inst, 0); // load源地址
                    String ptrName = LLVMGetValueName(ptr).getString();

                    if (!globalVariables.contains(ptrName) && allocaInsts.containsKey(ptrName)) {
                        loads.get(ptrName).add(inst);
                    }
                }
            }
        }

        // 第二阶段：向后分析活跃性，从ret指令开始
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            LLVMValueRef terminator = LLVMGetBasicBlockTerminator(bb);
            if (terminator != null && LLVMGetInstructionOpcode(terminator) == LLVMRet) {
                // 如果ret指令返回的是一个加载的值，则该值是活跃的
                if (LLVMGetNumOperands(terminator) > 0) {
                    LLVMValueRef retVal = LLVMGetOperand(terminator, 0);
                    markLiveValue(retVal, liveVars, loads);
                }
            }
        }

        // 第三阶段：标记死存储
        for (String varName : allocaInsts.keySet()) {
            // 如果变量不是活跃的，且有store指令
            if (!liveVars.contains(varName) && lastStores.containsKey(varName)) {
                // 所有对该变量的store都是死存储
                toRemove.add(lastStores.get(varName));
            }
        }

        // 第四阶段：删除标记的指令
        for (LLVMValueRef inst : toRemove) {
            LLVMInstructionEraseFromParent(inst);
        }

        return !toRemove.isEmpty();
    }

    /**
     * 递归标记活跃值
     *
     * @param value    要检查的值
     * @param liveVars 活跃变量集合
     * @param loads    变量加载指令映射
     */
    private void markLiveValue(LLVMValueRef value, Set<String> liveVars, Map<String, Set<LLVMValueRef>> loads) {
        // 检查这个值是否是load指令的结果
        for (Map.Entry<String, Set<LLVMValueRef>> entry : loads.entrySet()) {
            if (entry.getValue().contains(value)) {
                // 标记源变量为活跃
                liveVars.add(entry.getKey());
                break;
            }
        }

        // 如果值是指令结果，递归检查它的操作数
        if (LLVMIsAInstruction(value) != null) {
            int numOperands = LLVMGetNumOperands(value);
            for (int i = 0; i < numOperands; i++) {
                LLVMValueRef operand = LLVMGetOperand(value, i);
                markLiveValue(operand, liveVars, loads);
            }
        }
    }
}
