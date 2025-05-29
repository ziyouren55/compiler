import org.bytedeco.llvm.LLVM.*;
import org.bytedeco.llvm.global.LLVM;
import org.llvm4j.llvm4j.Module;
import org.llvm4j.optional.Option;

import java.io.File;
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
//    String op_ll_output = "./tests/ll_out/op_output.ll";

    // 全局CFG字段
    private Map<BlockState, Set<BlockState>> successors = new HashMap<>();
    private Map<BlockState, Set<BlockState>> predecessors = new HashMap<>();

    /**
     * 基本块状态类，包含基本块引用及其名称
     */
    private static class BlockState {
        private LLVMBasicBlockRef block; // 基本块引用
        private String blockName; // 基本块名称

        public BlockState(LLVMBasicBlockRef block) {
            this.block = block;
            this.blockName = LLVMGetBasicBlockName(block).getString();
        }

        public LLVMBasicBlockRef getBlock() {
            return block;
        }

        public String getBlockName() {
            return blockName;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof BlockState))
                return false;
            BlockState other = (BlockState) obj;

            return this.block.equals(other.block);
        }

        @Override
        public int hashCode() {
            return block.hashCode();
        }

        @Override
        public String toString() {
            return blockName;
        }
    }

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
//                new Module(module).dump(Option.of(new File(op_ll_output)));
            }
        }

        // todo 这两步都有问题

        // 然后尝试消除分支不可达代码
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                changed |= eliminateUnreachableCode(func);
//                new Module(module).dump(Option.of(new File(op_ll_output)));
            }
        }

        // 最后尝试消除冗余跳转
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                changed |= eliminateRedundantJumps(func);
//                new Module(module).dump(Option.of(new File(op_ll_output)));
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
        this.successors = buildCFG(func);
        this.predecessors = buildPredecessors(successors);

        // 检测条件分支的条件是否为常量
        changed |= simplifyConstantBranches(func);

//        new Module(module).dump(Option.of(new File(op_ll_output)));

        // 查找没有前驱的基本块（除了入口块）
        Set<BlockState> unreachableBlocks = findUnreachableBlocks(func, predecessors, successors);

        // 移除不可达块
        // todo 同步前继和后继
        for (BlockState blockState : unreachableBlocks) {
            // 1. 移除该块作为其他块的后继
            for (BlockState pred : predecessors.getOrDefault(blockState, new HashSet<>())) {
                successors.getOrDefault(pred, new HashSet<>()).remove(blockState);
            }
            // 2. 移除该块作为其他块的前继
            for (BlockState succ : successors.getOrDefault(blockState, new HashSet<>())) {
                predecessors.getOrDefault(succ, new HashSet<>()).remove(blockState);
            }
            // 3. 从全局Map中移除该块
            successors.remove(blockState);
            predecessors.remove(blockState);
            // 4. 从IR中移除该块
            LLVMRemoveBasicBlockFromParent(blockState.getBlock());
            changed = true;
        }
//        new Module(module).dump(Option.of(new File(op_ll_output)));

        return changed;
    }

    /**
     * 查找不可达的基本块
     */
    private Set<BlockState> findUnreachableBlocks(LLVMValueRef func,
            Map<BlockState, Set<BlockState>> predecessors,
            Map<BlockState, Set<BlockState>> successors) {
        Set<BlockState> unreachableBlocks = new HashSet<>();
        BlockState entryBlockState = new BlockState(LLVMGetEntryBasicBlock(func));

        // 执行可达性分析
        Set<BlockState> reachableBlocks = new HashSet<>();
        Queue<BlockState> worklist = new LinkedList<>();

        // 从入口块开始
        worklist.add(entryBlockState);
        reachableBlocks.add(entryBlockState);

        // 广度优先搜索所有可达块
        while (!worklist.isEmpty()) {
            BlockState blockState = worklist.poll();

            // 将所有后继加入工作列表
            for (BlockState succ : successors.getOrDefault(blockState, Collections.emptySet())) {
                if (!reachableBlocks.contains(succ)) {
                    reachableBlocks.add(succ);
                    worklist.add(succ);
                }
            }
        }

        // 收集所有不可达的块
        Map<LLVMBasicBlockRef, BlockState> blockStates = new HashMap<>();
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            BlockState blockState = new BlockState(bb);
            blockStates.put(bb, blockState);
            if (!reachableBlocks.contains(blockState)) {
                unreachableBlocks.add(blockState);
            }
        }

        return unreachableBlocks;
    }

    /**
     * 构建控制流图
     */
    private Map<BlockState, Set<BlockState>> buildCFG(LLVMValueRef func) {
        Map<BlockState, Set<BlockState>> successors = new HashMap<>();
        Map<LLVMBasicBlockRef, BlockState> blockStateMap = new HashMap<>();

        // 首先创建所有BlockState并存放到映射中
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            BlockState blockState = new BlockState(bb);
            blockStateMap.put(bb, blockState);
            successors.put(blockState, new HashSet<>());
        }

        // 构建基本块之间的边
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            BlockState blockState = blockStateMap.get(bb);

            LLVMValueRef terminator = LLVMGetBasicBlockTerminator(bb);
            if (terminator != null) {
                int opcode = LLVMGetInstructionOpcode(terminator);

                if (opcode == LLVMBr) {
                    int numOperands = LLVMGetNumOperands(terminator);

                    if (numOperands == 3) { // 条件分支
                        LLVMBasicBlockRef trueBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 2));
                        LLVMBasicBlockRef falseBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 1));

                        successors.get(blockState).add(blockStateMap.get(trueBlock));
                        successors.get(blockState).add(blockStateMap.get(falseBlock));
                    } else if (numOperands == 1) { // 无条件分支
                        LLVMBasicBlockRef destBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 0));
                        successors.get(blockState).add(blockStateMap.get(destBlock));
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
    private Map<BlockState, Set<BlockState>> buildPredecessors(
            Map<BlockState, Set<BlockState>> successors) {
        Map<BlockState, Set<BlockState>> predecessors = new HashMap<>();

        for (Map.Entry<BlockState, Set<BlockState>> entry : successors.entrySet()) {
            BlockState from = entry.getKey();
            for (BlockState to : entry.getValue()) {
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
            String BBName = LLVMGetBasicBlockName(bb).getString();
            if (terminator != null && LLVMGetInstructionOpcode(terminator) == LLVMBr) {
                int numOperands = LLVMGetNumOperands(terminator);
                if (numOperands == 3) { // 条件分支
                    LLVMValueRef condition = LLVMGetOperand(terminator, 0);
                    String condName = LLVMPrintValueToString(condition).getString();

                    // 检查条件是否是常量
                    if (LLVMIsAConstantInt(condition) != null) {
                        boolean condValue = LLVMConstIntGetSExtValue(condition) != 0;
                        LLVMBasicBlockRef trueBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 2));
                        LLVMBasicBlockRef falseBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 1));
                        String trueBBName = LLVMGetBasicBlockName(trueBlock).getString();
                        String falseBBName = LLVMGetBasicBlockName(falseBlock).getString();

                        condBranchesToSimplify.add(new ConditionalBranchInfo(
                                bb, terminator, condValue, trueBlock, falseBlock));
                    }
                }
            }
        }

        // 然后简化这些条件分支
        for (ConditionalBranchInfo info : condBranchesToSimplify) {
            // 更新CFG：移除原有分支，添加新分支
            BlockState curBlock = new BlockState(info.block);
            BlockState trueBlockState = new BlockState(info.trueBlock);
            BlockState falseBlockState = new BlockState(info.falseBlock);
            BlockState targetBlock = info.condValue ? trueBlockState : falseBlockState;
            // 1. 移除旧的后继
            successors.getOrDefault(curBlock, new HashSet<>()).remove(trueBlockState);
            successors.getOrDefault(curBlock, new HashSet<>()).remove(falseBlockState);
            // 2. 添加新的后继
            successors.computeIfAbsent(curBlock, k -> new HashSet<>()).add(targetBlock);
            // 3. 更新前继
            predecessors.getOrDefault(trueBlockState, new HashSet<>()).remove(curBlock);
            predecessors.getOrDefault(falseBlockState, new HashSet<>()).remove(curBlock);
            predecessors.computeIfAbsent(targetBlock, k -> new HashSet<>()).add(curBlock);

            // 创建一个新的无条件分支指令
            LLVMBuilderRef builder = LLVMCreateBuilder();
            LLVMPositionBuilderAtEnd(builder, info.block);

            // 条件为真，跳转到true块；否则跳转到false块
            LLVMBasicBlockRef targetBlockRef = info.condValue ? info.trueBlock : info.falseBlock;
            String targetBlockName = LLVMGetBasicBlockName(targetBlockRef).getString();
            // 先删除原始的条件分支
            LLVMInstructionEraseFromParent(info.branchInst);

            // 创建无条件分支
            LLVMBuildBr(builder, targetBlockRef);

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
        // 使用全局CFG
        Map<LLVMBasicBlockRef, BlockState> blockStateMap = new HashMap<>();
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            BlockState blockState = new BlockState(bb);
            blockStateMap.put(bb, blockState);
        }

        boolean merged;
        do {
            merged = false;
            List<BlockMergeInfo> blocksToMerge = new ArrayList<>();
            // 收集当前可合并的块对
            for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
                BlockState blockState = blockStateMap.get(bb);
                LLVMValueRef terminator = LLVMGetBasicBlockTerminator(bb);

                // 检查是否是无条件跳转
                if (terminator != null && LLVMGetInstructionOpcode(terminator) == LLVMBr
                        && LLVMGetNumOperands(terminator) == 1) {
                    LLVMBasicBlockRef targetBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 0));
                    BlockState targetBlockState = blockStateMap.get(targetBlock);

                    // 检查目标块是否只有这一个前驱
                    Set<BlockState> preds = predecessors.getOrDefault(targetBlockState, Collections.emptySet());
                    if (preds.size() == 1 && preds.contains(blockState) && !bb.equals(targetBlock)) {
                        blocksToMerge.add(new BlockMergeInfo(bb, targetBlock, terminator));
                    }
                }
            }
            // 执行本轮所有可合并的块对
            for (BlockMergeInfo info : blocksToMerge) {
                // 检查source和target是否还在CFG中
                BlockState sourceState = blockStateMap.get(info.sourceBlock);
                BlockState targetState = blockStateMap.get(info.targetBlock);
                if (sourceState == null || targetState == null)
                    continue;
                // 1. 移除跳转指令
                LLVMInstructionEraseFromParent(info.branchInst);
                // 2. 将目标块中的所有指令移动到当前块
                moveInstructionsToBlock(info.targetBlock, info.sourceBlock);
                // 3. 更新CFG
                // a. 将source的所有后继（除了target）转移到target
                for (BlockState succ : new HashSet<>(successors.getOrDefault(sourceState, Collections.emptySet()))) {
                    if (!succ.equals(targetState)) {
                        successors.get(targetState).add(succ);
                        predecessors.get(succ).remove(sourceState);
                        predecessors.get(succ).add(targetState);
                    }
                }
                // b. 将target的所有前驱中的source替换为source的所有前驱
                for (BlockState pred : new HashSet<>(predecessors.getOrDefault(sourceState, Collections.emptySet()))) {
                    if (!pred.equals(targetState)) {
                        successors.get(pred).remove(sourceState);
                        successors.get(pred).add(targetState);
                        predecessors.get(targetState).add(pred);
                    }
                }
                // c. 移除source和target之间的前驱/后继关系
                successors.getOrDefault(sourceState, new HashSet<>()).remove(targetState);
                predecessors.getOrDefault(targetState, new HashSet<>()).remove(sourceState);
                // d. 移除source块的所有CFG信息
                successors.remove(sourceState);
                predecessors.remove(sourceState);
                // 4. 删除目标块（现在应该是空的）
                LLVMRemoveBasicBlockFromParent(info.targetBlock);
                // 5. 更新blockStateMap
                blockStateMap.remove(info.targetBlock);
                merged = true;
                changed = true;
            }
        } while (merged);
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

        String trueBBName;
        String falseBBName;
        String BBName;
        String instStr;

        public ConditionalBranchInfo(LLVMBasicBlockRef block, LLVMValueRef branchInst,
                boolean condValue, LLVMBasicBlockRef trueBlock,
                LLVMBasicBlockRef falseBlock) {
            this.block = block;
            this.branchInst = branchInst;
            this.condValue = condValue;
            this.trueBlock = trueBlock;
            this.falseBlock = falseBlock;
            this.trueBBName = LLVMGetBasicBlockName(trueBlock).getString();
            this.falseBBName = LLVMGetBasicBlockName(falseBlock).getString();
            this.BBName = LLVMGetBasicBlockName(block).getString();
            this.instStr = LLVMPrintValueToString(branchInst).getString();
        }
    }

    /**
     * 基本块合并信息类
     */
    private static class BlockMergeInfo {
        public LLVMBasicBlockRef sourceBlock;
        public LLVMBasicBlockRef targetBlock;
        public LLVMValueRef branchInst;
        String sourceName;
        String targetName;
        String instName;

        public BlockMergeInfo(LLVMBasicBlockRef sourceBlock, LLVMBasicBlockRef targetBlock, LLVMValueRef branchInst) {
            this.sourceBlock = sourceBlock;
            this.targetBlock = targetBlock;
            this.branchInst = branchInst;
            this.sourceName = LLVMGetBasicBlockName(sourceBlock).getString();
            this.targetName = LLVMGetBasicBlockName(targetBlock).getString();
            this.instName = LLVMPrintValueToString(branchInst).getString();
        }
    }
}
