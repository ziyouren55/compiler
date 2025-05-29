import org.bytedeco.llvm.LLVM.*;
import org.llvm4j.llvm4j.Module;
import org.llvm4j.optional.Option;

import java.io.File;
import java.util.*;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Lab6 LLVM优化器
 * 集成常量传播、未使用变量消除和死代码消除三种优化
 * 实现迭代优化，直到达到不动点
 */
public class LLVMOptimizerForLab6 {

    private LLVMModuleRef module;
    private ConstantPropagationOptimizer constantPropagation;
    private ImprovedUnusedVarEliminator unusedVarElimination;
    private DeadCodeEliminationOptimizer deadCodeElimination;
    private BooleanExpressionOptimizer booleanExpressionOptimizer;

    // 使用Map来保存每个函数的状态
    private Map<String, FunctionState> previousStates = new HashMap<>();

    String op_ll_output = "./tests/ll_out/op_output.ll";
    String ll_output = "./tests/ll_out/output.ll";

    /**
     * 构造函数
     *
     * @param module LLVM模块
     */
    public LLVMOptimizerForLab6(LLVMModuleRef module) {
        this.module = module;
        // 初始化三个优化器
        this.constantPropagation = new ConstantPropagationOptimizer(module);
        this.unusedVarElimination = new ImprovedUnusedVarEliminator(module);
        this.deadCodeElimination = new DeadCodeEliminationOptimizer(module);
        this.booleanExpressionOptimizer = new BooleanExpressionOptimizer(module);

        // 设置死代码消除优化器的常量传播引用，用于获取条件分支的常量值
        this.deadCodeElimination.setConstantPropagationOptimizer(constantPropagation);

        // 初始化模块状态
        captureModuleState();
    }

    /**
     * 运行优化
     *
     * @return 优化后的模块
     */
    public LLVMModuleRef optimize() {
        boolean changed = false;
        int iterationCount = 0;
        final int MAX_ITERATIONS = 10; // 设置最大迭代次数，防止意外的无限循环
        // new Module(module).dump(Option.of(new File(ll_output)));
        booleanExpressionOptimizer.run();

        do {

            // 应用常量传播优化
            boolean changed1 = constantPropagation.run();

//            // 应用死代码消除优化（使用常量传播的结果）
//            boolean changed2 = deadCodeElimination.run();

            // 应用未使用变量消除优化
            boolean changed3 = unusedVarElimination.run();

            // 检查是否有任何变化
            changed = changed1 || changed3;

            // 更新每个优化器的模块引用
            if (changed) {
                constantPropagation = new ConstantPropagationOptimizer(module);
                unusedVarElimination = new ImprovedUnusedVarEliminator(module);
                deadCodeElimination = new DeadCodeEliminationOptimizer(module);
                deadCodeElimination.setConstantPropagationOptimizer(constantPropagation);
            }

            iterationCount++;
        } while (changed && iterationCount < MAX_ITERATIONS);

        return module;
    }

    /**
     * 捕获模块的当前状态
     */
    private void captureModuleState() {
        previousStates.clear();

        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                String funcName = LLVMGetValueName(func).getString();
                int basicBlockCount = LLVMCountBasicBlocks(func);
                int instructionCount = countInstructions(func);

                previousStates.put(funcName, new FunctionState(basicBlockCount, instructionCount));
            }
        }
    }

    /**
     * 检查模块是否发生变化
     *
     * @return 如果模块发生变化返回true，否则返回false
     */
    private boolean checkForChanges() {
        boolean changed = false;
        Map<String, FunctionState> currentStates = new HashMap<>();

        // 收集当前状态
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                String funcName = LLVMGetValueName(func).getString();
                int basicBlockCount = LLVMCountBasicBlocks(func);
                int instructionCount = countInstructions(func);

                currentStates.put(funcName, new FunctionState(basicBlockCount, instructionCount));

                // 与之前的状态比较
                FunctionState prevState = previousStates.get(funcName);
                if (prevState == null ||
                        prevState.basicBlockCount != basicBlockCount ||
                        prevState.instructionCount != instructionCount) {
                    changed = true;
                }
            }
        }

        // 检查是否有函数被完全删除
        if (previousStates.size() != currentStates.size()) {
            changed = true;
        }

        // 更新状态
        previousStates = currentStates;

        return changed;
    }

    /**
     * 统计函数中的指令数量
     *
     * @param func 要统计的函数
     * @return 指令数量
     */
    private int countInstructions(LLVMValueRef func) {
        int count = 0;

        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                count++;
            }
        }

        return count;
    }

    /**
     * 内部类，用于存储函数的状态信息
     */
    private static class FunctionState {
        public final int basicBlockCount;
        public final int instructionCount;

        public FunctionState(int basicBlockCount, int instructionCount) {
            this.basicBlockCount = basicBlockCount;
            this.instructionCount = instructionCount;
        }
    }
}
