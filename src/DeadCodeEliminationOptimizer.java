import org.bytedeco.llvm.LLVM.*;
import java.util.*;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * 死代码消除优化器类
 * 实现三种死代码消除:
 * 1. 分支不可达代码消除：删除永远不会执行的代码块
 * 2. 冗余跳转消除：消除多余的跳转指令并合并基本块
 * 3. 死存储消除：消除被覆盖而未被读取的store指令
 */
public class DeadCodeEliminationOptimizer {

    private LLVMModuleRef module;
    private ConstantPropagationOptimizer constPropOptimizer;

    /**
     * 构造函数
     *
     * @param module LLVM模块
     */
    public DeadCodeEliminationOptimizer(LLVMModuleRef module) {
        this.module = module;
        this.constPropOptimizer = null; // 会在需要时进行懒初始化
    }

    /**
     * 设置常量传播优化器的引用，用于获取条件分支的常量值
     *
     * @param constPropOptimizer 常量传播优化器实例
     */
    public void setConstantPropagationOptimizer(ConstantPropagationOptimizer constPropOptimizer) {
        this.constPropOptimizer = constPropOptimizer;
    }

    /**
     * 运行死代码消除优化
     *
     * @return 是否进行了优化
     */
    public boolean run() {
        boolean changed = false;

        // 首先尝试消除死存储
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                changed |= eliminateDeadStores(func);
            }
        }

        // 然后尝试消除分支不可达代码
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                changed |= eliminateUnreachableCode(func);
            }
        }

        // 最后尝试消除冗余跳转
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                changed |= eliminateRedundantJumps(func);
            }
        }

        return changed;
    }

    /**
     * 消除死存储 - 被覆盖且从未被读取的store指令
     *
     * @param func 待优化的函数
     * @return 是否进行了优化
     */
    private boolean eliminateDeadStores(LLVMValueRef func) {
        boolean changed = false;

        // 遍历每个基本块
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            // 跟踪每个地址的最后一次写入
            Map<String, LLVMValueRef> lastStore = new HashMap<>();
            // 跟踪死存储指令
            Set<LLVMValueRef> deadStores = new HashSet<>();
            // 跟踪已经被读取的地址
            Set<String> readAddresses = new HashSet<>();

            // 遍历基本块中的所有指令
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                if (LLVMGetInstructionOpcode(inst) == LLVMStore) {
                    LLVMValueRef valueOp = LLVMGetOperand(inst, 0); // 存储的值
                    LLVMValueRef ptrOp = LLVMGetOperand(inst, 1); // 存储的地址
                    String ptrName = LLVMGetValueName(ptrOp).getString();

                    // 如果这是一个局部变量（检查是否是由alloca分配的）
                    if (ptrName != null && !ptrName.isEmpty()) {
                        // 如果之前有对该地址的写入，且该写入未被读取，则之前的写入是死存储
                        if (lastStore.containsKey(ptrName) && !readAddresses.contains(ptrName)) {
                            deadStores.add(lastStore.get(ptrName));
                        }

                        // 更新该地址的最后一次写入
                        lastStore.put(ptrName, inst);

                        // 每次store之后重置该地址的读取状态
                        readAddresses.remove(ptrName);
                    }
                } else if (LLVMGetInstructionOpcode(inst) == LLVMLoad) {
                    LLVMValueRef ptrOp = LLVMGetOperand(inst, 0); // 读取的地址
                    String ptrName = LLVMGetValueName(ptrOp).getString();

                    if (ptrName != null && !ptrName.isEmpty()) {
                        // 标记该地址已被读取
                        readAddresses.add(ptrName);
                    }
                } else if (LLVMIsACallInst(inst) != null) {
                    // 对于函数调用，保守地假设所有地址可能被读取
                    // 如果是一个更复杂的分析，这里可以检查函数的参数类型和可能的别名
                    readAddresses.addAll(lastStore.keySet());
                }
            }

            // 删除所有标记为死存储的指令
            for (LLVMValueRef deadStore : deadStores) {
                LLVMInstructionEraseFromParent(deadStore);
                changed = true;
            }
        }

        return changed;
    }

    /**
     * 消除分支不可达代码
     *
     * @param func 待优化的函数
     * @return 是否进行了优化
     */
    private boolean eliminateUnreachableCode(LLVMValueRef func) {
        boolean changed = false;

        // 构建基本块之间的控制流图
        Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> successors = buildCFG(func);
        Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> predecessors = buildPredecessors(successors);

        // 检测条件分支的条件是否为常量
        changed |= simplifyConstantBranches(func);

        // 查找没有前驱的基本块（除了入口块）
        Set<LLVMBasicBlockRef> unreachableBlocks = findUnreachableBlocks(func, predecessors, successors);

        // 移除不可达块
        for (LLVMBasicBlockRef bb : unreachableBlocks) {
            LLVMRemoveBasicBlockFromParent(bb);
            changed = true;
        }

        return changed;
    }

    /**
     * 查找不可达的基本块
     */
    private Set<LLVMBasicBlockRef> findUnreachableBlocks(LLVMValueRef func,
            Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> predecessors,
            Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> successors) {
        Set<LLVMBasicBlockRef> unreachableBlocks = new HashSet<>();
        LLVMBasicBlockRef entryBlock = LLVMGetEntryBasicBlock(func);

        // 执行可达性分析
        Set<LLVMBasicBlockRef> reachableBlocks = new HashSet<>();
        Queue<LLVMBasicBlockRef> worklist = new LinkedList<>();

        // 从入口块开始
        worklist.add(entryBlock);
        reachableBlocks.add(entryBlock);

        // 广度优先搜索所有可达块
        while (!worklist.isEmpty()) {
            LLVMBasicBlockRef bb = worklist.poll();

            // 将所有后继加入工作列表
            for (LLVMBasicBlockRef succ : successors.getOrDefault(bb, Collections.emptySet())) {
                if (!reachableBlocks.contains(succ)) {
                    reachableBlocks.add(succ);
                    worklist.add(succ);
                }
            }
        }

        // 收集所有不可达的块
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            if (!reachableBlocks.contains(bb)) {
                unreachableBlocks.add(bb);
            }
        }

        return unreachableBlocks;
    }

    /**
     * 构建控制流图
     */
    private Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> buildCFG(LLVMValueRef func) {
        Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> successors = new HashMap<>();

        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            successors.put(bb, new HashSet<>());

            LLVMValueRef terminator = LLVMGetBasicBlockTerminator(bb);
            if (terminator != null) {
                int opcode = LLVMGetInstructionOpcode(terminator);

                if (opcode == LLVMBr) {
                    int numOperands = LLVMGetNumOperands(terminator);

                    if (numOperands == 3) { // 条件分支
                        LLVMBasicBlockRef trueBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 2));
                        LLVMBasicBlockRef falseBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 1));

                        successors.get(bb).add(trueBlock);
                        successors.get(bb).add(falseBlock);
                    } else if (numOperands == 1) { // 无条件分支
                        LLVMBasicBlockRef destBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 0));
                        successors.get(bb).add(destBlock);
                    }
                }
                // 返回指令没有后继
            }
        }

        return successors;
    }

    /**
     * 构建基本块的前驱映射
     */
    private Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> buildPredecessors(
            Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> successors) {
        Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> predecessors = new HashMap<>();

        for (Map.Entry<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> entry : successors.entrySet()) {
            LLVMBasicBlockRef from = entry.getKey();
            for (LLVMBasicBlockRef to : entry.getValue()) {
                predecessors.computeIfAbsent(to, k -> new HashSet<>()).add(from);
            }
        }

        return predecessors;
    }

    /**
     * 简化常量条件分支
     */
    private boolean simplifyConstantBranches(LLVMValueRef func) {
        boolean changed = false;
        List<ConditionalBranchInfo> condBranchesToSimplify = new ArrayList<>();

        // 先收集所有条件为常量的条件分支
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            LLVMValueRef terminator = LLVMGetBasicBlockTerminator(bb);
            if (terminator != null && LLVMGetInstructionOpcode(terminator) == LLVMBr) {
                int numOperands = LLVMGetNumOperands(terminator);
                if (numOperands == 3) { // 条件分支
                    LLVMValueRef condition = LLVMGetOperand(terminator, 0);

                    // 检查条件是否是常量
                    if (LLVMIsAConstantInt(condition) != null) {
                        boolean condValue = LLVMConstIntGetSExtValue(condition) != 0;
                        LLVMBasicBlockRef trueBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 2));
                        LLVMBasicBlockRef falseBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 1));

                        condBranchesToSimplify.add(new ConditionalBranchInfo(
                                bb, terminator, condValue, trueBlock, falseBlock));
                    }
                }
            }
        }

        // 然后简化这些条件分支
        for (ConditionalBranchInfo info : condBranchesToSimplify) {
            // 创建一个新的无条件分支指令
            LLVMBuilderRef builder = LLVMCreateBuilder();
            LLVMPositionBuilderAtEnd(builder, info.block);

            // 条件为真，跳转到true块；否则跳转到false块
            LLVMBasicBlockRef targetBlock = info.condValue ? info.trueBlock : info.falseBlock;

            // 先删除原始的条件分支
            LLVMInstructionEraseFromParent(info.branchInst);

            // 创建无条件分支
            LLVMBuildBr(builder, targetBlock);

            LLVMDisposeBuilder(builder);
            changed = true;
        }

        return changed;
    }

    /**
     * 消除冗余跳转指令
     */
    private boolean eliminateRedundantJumps(LLVMValueRef func) {
        boolean changed = false;
        Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> predecessors = buildPredecessors(buildCFG(func));

        // 标记要合并的基本块对
        List<BlockMergeInfo> blocksToMerge = new ArrayList<>();

        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            LLVMValueRef terminator = LLVMGetBasicBlockTerminator(bb);

            // 检查是否是无条件跳转
            if (terminator != null && LLVMGetInstructionOpcode(terminator) == LLVMBr
                    && LLVMGetNumOperands(terminator) == 1) {
                LLVMBasicBlockRef targetBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 0));

                // 检查目标块是否只有这一个前驱
                Set<LLVMBasicBlockRef> preds = predecessors.getOrDefault(targetBlock, Collections.emptySet());
                if (preds.size() == 1 && preds.contains(bb) && bb != targetBlock) {
                    blocksToMerge.add(new BlockMergeInfo(bb, targetBlock, terminator));
                }
            }
        }

        // 执行基本块合并
        for (BlockMergeInfo info : blocksToMerge) {
            // 移除跳转指令
            LLVMInstructionEraseFromParent(info.branchInst);

            // 将目标块中的所有指令移动到当前块
            moveInstructionsToBlock(info.targetBlock, info.sourceBlock);

            // 删除目标块（现在应该是空的）
            LLVMRemoveBasicBlockFromParent(info.targetBlock);

            changed = true;
        }

        return changed;
    }

    /**
     * 将源块中的所有指令移动到目标块
     */
    private void moveInstructionsToBlock(LLVMBasicBlockRef sourceBlock, LLVMBasicBlockRef targetBlock) {
        List<LLVMValueRef> instructions = new ArrayList<>();

        // 收集源块中的所有指令
        for (LLVMValueRef inst = LLVMGetFirstInstruction(sourceBlock); inst != null; inst = LLVMGetNextInstruction(
                inst)) {
            instructions.add(inst);
        }

        // 创建构建器，定位到目标块的末尾
        LLVMBuilderRef builder = LLVMCreateBuilder();
        LLVMPositionBuilderAtEnd(builder, targetBlock);

        // 移动每条指令
        for (LLVMValueRef inst : instructions) {
            LLVMInstructionRemoveFromParent(inst);
            LLVMInsertIntoBuilder(builder, inst);
        }

        LLVMDisposeBuilder(builder);
    }

    /**
     * 条件分支信息类
     */
    private static class ConditionalBranchInfo {
        public LLVMBasicBlockRef block;
        public LLVMValueRef branchInst;
        public boolean condValue;
        public LLVMBasicBlockRef trueBlock;
        public LLVMBasicBlockRef falseBlock;

        public ConditionalBranchInfo(LLVMBasicBlockRef block, LLVMValueRef branchInst,
                boolean condValue, LLVMBasicBlockRef trueBlock,
                LLVMBasicBlockRef falseBlock) {
            this.block = block;
            this.branchInst = branchInst;
            this.condValue = condValue;
            this.trueBlock = trueBlock;
            this.falseBlock = falseBlock;
        }
    }

    /**
     * 基本块合并信息类
     */
    private static class BlockMergeInfo {
        public LLVMBasicBlockRef sourceBlock;
        public LLVMBasicBlockRef targetBlock;
        public LLVMValueRef branchInst;

        public BlockMergeInfo(LLVMBasicBlockRef sourceBlock, LLVMBasicBlockRef targetBlock, LLVMValueRef branchInst) {
            this.sourceBlock = sourceBlock;
            this.targetBlock = targetBlock;
            this.branchInst = branchInst;
        }
    }
}