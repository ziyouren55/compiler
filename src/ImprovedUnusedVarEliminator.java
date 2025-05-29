import org.bytedeco.llvm.LLVM.*;
import java.util.*;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * 改进的未使用变量消除优化器
 * 识别并消除只定义而未被使用的局部变量
 */
public class ImprovedUnusedVarEliminator {

    private LLVMModuleRef module;
    // 用于记录全局变量名，这些变量不应被消除
    private Set<String> globalVariables = new HashSet<>();

    /**
     * 构造函数
     *
     * @param module LLVM模块
     */
    public ImprovedUnusedVarEliminator(LLVMModuleRef module) {
        this.module = module;
        collectGlobalVariables();
    }

    /**
     * 收集模块中的所有全局变量名
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
     * @return 如果进行了优化返回true，否则返回false
     */
    public boolean run() {
        boolean changed = false;

        // 对每个函数进行未使用变量消除
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                changed |= eliminateUnusedVarsInFunction(func);
            }
        }

        return changed;
    }

    /**
     * 在单个函数中消除未使用变量
     *
     * @param func 待优化的函数
     * @return 如果进行了优化返回true，否则返回false
     */
    private boolean eliminateUnusedVarsInFunction(LLVMValueRef func) {
        // 收集所有局部变量（alloca指令）
        Map<String, LLVMValueRef> localVars = new HashMap<>();

        // 记录变量的使用情况
        Set<String> usedVars = new HashSet<>();

        // 记录对未使用变量的store指令
        Map<String, List<LLVMValueRef>> varStores = new HashMap<>();

        // 第一阶段：收集所有局部变量和store指令
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                if (LLVMGetInstructionOpcode(inst) == LLVMAlloca) {
                    String varName = LLVMGetValueName(inst).getString();
                    localVars.put(varName, inst);
                    varStores.put(varName, new ArrayList<>());
                } else if (LLVMGetInstructionOpcode(inst) == LLVMStore) {
                    LLVMValueRef ptr = LLVMGetOperand(inst, 1); // store指令的目标地址
                    String ptrName = LLVMGetValueName(ptr).getString();

                    // 如果存储到局部变量，记录store指令
                    if (localVars.containsKey(ptrName)) {
                        varStores.get(ptrName).add(inst);
                    }
                }
            }
        }

        // 第二阶段：找出所有被使用（被load）的变量
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                // 检查load指令
                if (LLVMGetInstructionOpcode(inst) == LLVMLoad) {
                    LLVMValueRef ptr = LLVMGetOperand(inst, 0); // load的源地址
                    String ptrName = LLVMGetValueName(ptr).getString();

                    // 如果加载的是局部变量，标记为已使用
                    if (localVars.containsKey(ptrName)) {
                        usedVars.add(ptrName);
                    }
                }

                // 检查变量是否被传递给函数调用
                if (LLVMIsACallInst(inst) != null) {
                    int numOperands = LLVMGetNumOperands(inst);
                    for (int i = 0; i < numOperands; i++) {
                        LLVMValueRef operand = LLVMGetOperand(inst, i);
                        if (LLVMIsALoadInst(operand) != null) {
                            LLVMValueRef loadPtr = LLVMGetOperand(operand, 0);
                            String loadPtrName = LLVMGetValueName(loadPtr).getString();
                            if (localVars.containsKey(loadPtrName)) {
                                usedVars.add(loadPtrName);
                            }
                        }
                    }
                }

                // 检查其他指令的操作数是否使用了加载的变量值
                if (LLVMIsAInstruction(inst) != null &&
                        LLVMGetInstructionOpcode(inst) != LLVMAlloca &&
                        LLVMGetInstructionOpcode(inst) != LLVMStore) {

                    int numOperands = LLVMGetNumOperands(inst);
                    for (int i = 0; i < numOperands; i++) {
                        LLVMValueRef operand = LLVMGetOperand(inst, i);
                        if (LLVMIsALoadInst(operand) != null) {
                            LLVMValueRef loadPtr = LLVMGetOperand(operand, 0);
                            String loadPtrName = LLVMGetValueName(loadPtr).getString();
                            if (localVars.containsKey(loadPtrName)) {
                                usedVars.add(loadPtrName);
                            }
                        }
                    }
                }
            }
        }

        // 第三阶段：找出和删除未使用变量
        boolean changed = false;
        List<LLVMValueRef> toRemove = new ArrayList<>();

        for (Map.Entry<String, LLVMValueRef> entry : localVars.entrySet()) {
            String varName = entry.getKey();
            LLVMValueRef allocaInst = entry.getValue();

            // 如果变量未被使用，标记alloca和相关store指令为删除
            if (!usedVars.contains(varName)) {
                // 首先将store指令添加到删除列表
                List<LLVMValueRef> storeInsts = varStores.get(varName);
                if (storeInsts != null) {
                    toRemove.addAll(storeInsts);
                }

                // 然后添加alloca指令
                toRemove.add(allocaInst);
                changed = true;
            }
        }

        // 删除标记的指令：先删除store，再删除alloca
        for (LLVMValueRef inst : toRemove) {
            LLVMInstructionEraseFromParent(inst);
        }

        return changed;
    }
}