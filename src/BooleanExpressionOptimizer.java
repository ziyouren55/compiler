import org.bytedeco.llvm.LLVM.*;
import java.util.*;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * 布尔表达式优化器
 * 优化IR中的冗余布尔转换模式，例如：
 * %cmp = icmp ... (产生i1值)
 * %zext = zext i1 %cmp to i32 (不必要地扩展为i32)
 * %neq = icmp ne i32 %zext, 0 (不必要地比较是否为0，转回i1)
 * 
 * 优化为直接使用原始的i1值
 */
public class BooleanExpressionOptimizer {

    private LLVMModuleRef module;

    /**
     * 构造函数
     *
     * @param module LLVM模块
     */
    public BooleanExpressionOptimizer(LLVMModuleRef module) {
        this.module = module;
    }

    /**
     * 运行布尔表达式优化
     *
     * @return 如果进行了优化返回true，否则返回false
     */
    public boolean run() {
        boolean changed = false;

        // 对每个函数进行优化
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                changed |= optimizeBooleanExpressions(func);
            }
        }

        return changed;
    }

    /**
     * 在单个函数中优化布尔表达式
     *
     * @param func 待优化的函数
     * @return 如果进行了优化返回true，否则返回false
     */
    private boolean optimizeBooleanExpressions(LLVMValueRef func) {
        boolean changed = false;
        List<LLVMValueRef> toRemove = new ArrayList<>();
        Map<LLVMValueRef, LLVMValueRef> replaceMap = new HashMap<>();

        // 首先收集所有符合条件的指令
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                // 检查是否为 icmp ne 指令
                if (LLVMIsAICmpInst(inst) != null && LLVMGetICmpPredicate(inst) == LLVMIntNE) {
                    // 获取操作数
                    LLVMValueRef op1 = LLVMGetOperand(inst, 0);
                    LLVMValueRef op2 = LLVMGetOperand(inst, 1);

                    // 检查第二个操作数是否为常量0
                    if (LLVMIsAConstantInt(op2) != null && LLVMConstIntGetSExtValue(op2) == 0) {
                        // 检查第一个操作数是否为zext指令
                        if (LLVMIsAZExtInst(op1) != null) {
                            // 获取zext的源操作数
                            LLVMValueRef zextSrc = LLVMGetOperand(op1, 0);

                            // 检查源操作数是否为i1类型
                            LLVMTypeRef srcType = LLVMTypeOf(zextSrc);
                            if (LLVMGetTypeKind(srcType) == LLVMIntegerTypeKind &&
                                    LLVMGetIntTypeWidth(srcType) == 1) {

                                // 我们找到了一个需要优化的模式
                                // 将原始icmp ne指令替换为zext的源操作数
                                replaceMap.put(inst, zextSrc);

                                // 如果zext只被这个icmp ne使用，也标记为删除
                                if (LLVMGetFirstUse(op1) != null && LLVMGetNextUse(LLVMGetFirstUse(op1)) == null) {
                                    toRemove.add(op1);
                                }

                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        // 应用替换
        for (Map.Entry<LLVMValueRef, LLVMValueRef> entry : replaceMap.entrySet()) {
            LLVMValueRef oldInst = entry.getKey();
            LLVMValueRef newValue = entry.getValue();
            LLVMReplaceAllUsesWith(oldInst, newValue);
            toRemove.add(oldInst);
        }

        // 删除不再需要的指令
        for (LLVMValueRef inst : toRemove) {
            LLVMInstructionEraseFromParent(inst);
        }

        return changed;
    }
}