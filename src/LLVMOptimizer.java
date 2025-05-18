import org.bytedeco.llvm.LLVM.*;

import static org.bytedeco.llvm.global.LLVM.*;

import java.util.*;

/**
 * LLVM IR 优化器类
 * 目前实现了三种优化：
 * 1. 冗余store-load模式消除
 * 2. 死存储消除
 * 3. 常量传播
 * 4. 全局变量更新修复
 */
public class LLVMOptimizer
{

    private LLVMModuleRef module;
    // 存储所有全局变量名，用于在多个方法中共享
    private Set<String> globalVariables = new HashSet<>();

    /**
     * 构造函数
     *
     * @param module 待优化的LLVM模块
     */
    public LLVMOptimizer(LLVMModuleRef module)
    {
        this.module = module;
        // 初始化收集所有全局变量
        collectGlobalVariables();
    }

    /**
     * 收集模块中的所有全局变量
     */
    private void collectGlobalVariables()
    {
        globalVariables.clear();
        for (LLVMValueRef global = LLVMGetFirstGlobal(module); global != null; global = LLVMGetNextGlobal(global))
        {
            String name = LLVMGetValueName(global).getString();
            globalVariables.add(name);
//            System.out.println("收集到全局变量: " + name);
        }
    }

    /**
     * 执行所有优化
     *
     * @return 优化后的模块
     */
    public LLVMModuleRef optimize()
    {
        // 遍历所有函数进行优化
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func))
        {

            // 跳过外部函数
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0)
            {
//                optimizeFunction(func);
//                eliminateDeadStores(func);
//                propagateConstants(func);
//                simplifyBranchConditions(func);
                // fixGlobalVariableUpdates(func);
            }
        }

        return module;
    }

    /**
     * 优化单个函数
     *
     * @param func 待优化的函数
     */
    private void optimizeFunction(LLVMValueRef func)
    {
        // 用于跟踪内存位置->存储的值的映射
        Map<String, LLVMValueRef> memoryValues = new HashMap<>();

        // 用于跟踪需要替换的值 (load结果 -> 原始存储值)
        Map<LLVMValueRef, LLVMValueRef> replacements = new HashMap<>();

        // 第一阶段：遍历所有指令，标识冗余的load操作
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
        {

            // 清空memoryValues，基本块边界不进行优化
            memoryValues.clear();

            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst))
            {

                int opcode = LLVMGetInstructionOpcode(inst);

                if (opcode == LLVMStore)
                {
                    // 处理store指令
                    LLVMValueRef value = LLVMGetOperand(inst, 0); // 存储的值
                    LLVMValueRef ptr = LLVMGetOperand(inst, 1); // 目标地址

                    String ptrName = LLVMGetValueName(ptr).getString();

                    if(LLVMIsAGlobalVariable(ptr) != null)
                        ptrName = "@" + ptrName;
                    memoryValues.put(ptrName, value);
                }
                else if (opcode == LLVMLoad)
                {
                    // 处理load指令
                    LLVMValueRef ptr = LLVMGetOperand(inst, 0); // 源地址
                    String ptrName = LLVMGetValueName(ptr).getString();

                    if(LLVMIsAGlobalVariable(ptr) != null)
                        ptrName = "@" + ptrName;

                    // 检查是否有最近存储到同一位置的值
                    if (memoryValues.containsKey(ptrName))
                    {
                        LLVMValueRef storedValue = memoryValues.get(ptrName);

                        // 如果有冲突，我们应该验证没有其他写入操作
                        boolean canReplace = true;

                        // TODO: 更全面的分析，检查在store和load之间是否有其他写入
                        // 对于简单版本，我们假设可以安全替换

                        if (canReplace)
                        {
                            // 标记此load为可替换
                            replacements.put(inst, storedValue);
                        }
                    }
                }
                else if (opcode == LLVMAlloca)
                {
                    // 记录局部变量，初始值为未知
                    String varName = LLVMGetValueName(inst).getString();
                    memoryValues.remove(varName); // 确保不会有旧值
                }
            }
        }

        // 第二阶段：使用收集的信息替换load指令的结果
        for (Map.Entry<LLVMValueRef, LLVMValueRef> entry : replacements.entrySet())
        {
            LLVMValueRef loadInst = entry.getKey();
            LLVMValueRef value = entry.getValue();

            // 替换所有使用load结果的指令
            LLVMReplaceAllUsesWith(loadInst, value);

            // 删除load指令
            LLVMInstructionEraseFromParent(loadInst);
        }

//        System.out.println("优化函数 " + LLVMGetValueName(func).getString() +
//            "，消除了 " + replacements.size() + " 个冗余load操作");
    }

    /**
     * 修复后的死存储消除优化
     * 考虑跨基本块的数据流
     *
     * @param func 待优化的函数
     */
    private void eliminateDeadStores(LLVMValueRef func)
    {
        // 跟踪每个内存位置对应的store指令列表
        Map<String, List<LLVMValueRef>> storeInstructions = new HashMap<>();

        // 跟踪store指令是否是死的（可删除的）
        Set<LLVMValueRef> deadStores = new HashSet<>();

        // 跟踪已经被load的内存位置
        Set<String> loadedLocations = new HashSet<>();

        // 记录每个基本块开始处的可能被load的变量
        Map<LLVMBasicBlockRef, Set<String>> blockEntryLoads = new HashMap<>();

        // 记录基本块间的跳转关系（前驱->后继）
        Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> successors = new HashMap<>();

        // 第一阶段：建立基本块之间的跳转关系
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
        {
            // 获取基本块的终结指令
            LLVMValueRef termInst = LLVMGetLastInstruction(bb);
            if (termInst != null && LLVMIsATerminatorInst(termInst) != null)
            {
                int opcode = LLVMGetInstructionOpcode(termInst);

                // 处理br指令
                if (opcode == LLVMBr)
                {
                    int numOperands = LLVMGetNumOperands(termInst);

                    // 条件分支：找到true和false目标
                    if (numOperands == 3)
                    {
                        LLVMBasicBlockRef trueBlock = LLVMValueAsBasicBlock(LLVMGetOperand(termInst, 1));
                        LLVMBasicBlockRef falseBlock = LLVMValueAsBasicBlock(LLVMGetOperand(termInst, 2));

                        // 添加到后继映射
                        successors.computeIfAbsent(bb, k -> new HashSet<>()).add(trueBlock);
                        successors.computeIfAbsent(bb, k -> new HashSet<>()).add(falseBlock);
                    }
                    // 无条件分支：只有一个目标
                    else if (numOperands == 1)
                    {
                        LLVMBasicBlockRef destBlock = LLVMValueAsBasicBlock(LLVMGetOperand(termInst, 0));
                        successors.computeIfAbsent(bb, k -> new HashSet<>()).add(destBlock);
                    }
                }
            }
        }

        // 第二阶段：收集每个基本块中使用的变量
        Map<String, Set<LLVMBasicBlockRef>> varUsedInBlocks = new HashMap<>();

        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
        {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst))
            {
                int opcode = LLVMGetInstructionOpcode(inst);

                if (opcode == LLVMLoad)
                {
                    LLVMValueRef ptr = LLVMGetOperand(inst, 0); // 源地址
                    String ptrName = LLVMGetValueName(ptr).getString();

                    // 记录变量在此基本块中被使用
                    varUsedInBlocks.computeIfAbsent(ptrName, k -> new HashSet<>()).add(bb);
                }
            }
        }

        // 第三阶段：标记每个基本块入口处可能使用的变量
        boolean changed;
        do
        {
            changed = false;

            for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
            {
                Set<String> entryLoads = blockEntryLoads.computeIfAbsent(bb, k -> new HashSet<>());
                int sizeBefore = entryLoads.size();

                // 检查此基本块是否直接使用变量
                for (Map.Entry<String, Set<LLVMBasicBlockRef>> entry : varUsedInBlocks.entrySet())
                {
                    if (entry.getValue().contains(bb))
                    {
                        entryLoads.add(entry.getKey());
                    }
                }

                // 如果有后继基本块，收集它们入口处使用的变量
                Set<LLVMBasicBlockRef> bbSuccessors = successors.get(bb);
                if (bbSuccessors != null)
                {
                    for (LLVMBasicBlockRef succ : bbSuccessors)
                    {
                        Set<String> succEntryLoads = blockEntryLoads.computeIfAbsent(succ, k -> new HashSet<>());
                        entryLoads.addAll(succEntryLoads);
                    }
                }

                if (entryLoads.size() > sizeBefore)
                {
                    changed = true;
                }
            }
        } while (changed);

        // 第四阶段：根据数据流分析结果识别死存储
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
        {
            // 清除上一个基本块的跟踪状态
            storeInstructions.clear();
            loadedLocations.clear();

            // 获取当前基本块的后继基本块
            Set<LLVMBasicBlockRef> bbSuccessors = successors.get(bb);
            Set<String> liveOnExit = new HashSet<>();

            // 收集所有后继基本块入口处使用的变量
            if (bbSuccessors != null)
            {
                for (LLVMBasicBlockRef succ : bbSuccessors)
                {
                    Set<String> succEntryLoads = blockEntryLoads.get(succ);
                    if (succEntryLoads != null)
                    {
                        liveOnExit.addAll(succEntryLoads);
                    }
                }
            }

            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst))
            {
                int opcode = LLVMGetInstructionOpcode(inst);

                if (opcode == LLVMStore)
                {
                    LLVMValueRef ptr = LLVMGetOperand(inst, 1); // 目标地址
                    String ptrName = LLVMGetValueName(ptr).getString();

                    // 检查是否是全局变量存储 - 保护全局变量
                    boolean isGlobalStore = isGlobalVariable(ptrName);

                    if (!isGlobalStore)
                    {
                        // 添加store指令到内存位置对应的列表
                        storeInstructions.computeIfAbsent(ptrName, k -> new ArrayList<>()).add(inst);

                        // 同一内存位置的前一个store指令是死的（除非之前被load过或者在基本块出口处是活跃的）
                        List<LLVMValueRef> stores = storeInstructions.get(ptrName);
                        if (stores.size() > 1 && !loadedLocations.contains(ptrName) && !liveOnExit.contains(ptrName))
                        {
                            deadStores.add(stores.get(stores.size() - 2));
                        }

                        // 重置该内存位置的load状态
                        loadedLocations.remove(ptrName);
                    }
                }
                else if (opcode == LLVMLoad)
                {
                    LLVMValueRef ptr = LLVMGetOperand(inst, 0); // 源地址
                    String ptrName = LLVMGetValueName(ptr).getString();

                    // 标记该内存位置已被load
                    loadedLocations.add(ptrName);
                }
            }

            // 在基本块末尾，不应该标记存储到后续基本块中使用的变量为死存储
            for (Map.Entry<String, List<LLVMValueRef>> entry : storeInstructions.entrySet())
            {
                String ptrName = entry.getKey();
                List<LLVMValueRef> stores = entry.getValue();

                if (!stores.isEmpty() && !loadedLocations.contains(ptrName))
                {
                    // 只有当变量在后续基本块中不使用时，才能标记其存储为死存储
                    if (!liveOnExit.contains(ptrName))
                    {
                        deadStores.add(stores.get(stores.size() - 1));
                    }
                }
            }
        }

        // 最后阶段：删除确认为死的存储指令
        for (LLVMValueRef deadStore : deadStores)
        {
            LLVMInstructionEraseFromParent(deadStore);
        }

//        System.out.println("优化函数 " + LLVMGetValueName(func).getString() +
//            "，消除了 " + deadStores.size() + " 个死存储操作");
    }


    private void propagateConstants(LLVMValueRef func)
    {
        // 跟踪变量到常量值的映射
        Map<String, LLVMValueRef> constantValues = new HashMap<>();

        // 需要替换的指令及其常量值
        Map<LLVMValueRef, LLVMValueRef> replacements = new HashMap<>();

        // 已处理的常量折叠指令
        Set<LLVMValueRef> foldedInstructions = new HashSet<>();

        // 跟踪在基本块中被修改的变量
        Map<LLVMBasicBlockRef, Set<String>> modifiedVars = new HashMap<>();

        // 跟踪基本块之间的跳转关系
        Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> successors = new HashMap<>();
        Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> predecessors = new HashMap<>();

        // 新增：跟踪在条件分支中被赋值的变量
        Set<String> variablesAssignedInBranches = new HashSet<>();

        // 新增：记录条件分支基本块及其目标
        Map<LLVMBasicBlockRef, List<LLVMBasicBlockRef>> conditionalBranches = new HashMap<>();

        // 第一阶段：建立基本块的控制流图并收集条件分支信息
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
        {
            // 获取基本块的终结指令
            LLVMValueRef termInst = LLVMGetLastInstruction(bb);
            if (termInst != null && LLVMIsATerminatorInst(termInst) != null)
            {
                int opcode = LLVMGetInstructionOpcode(termInst);

                // 处理br指令
                if (opcode == LLVMBr)
                {
                    int numOperands = LLVMGetNumOperands(termInst);

                    // 条件分支
                    if (numOperands == 3)
                    {
                        LLVMBasicBlockRef trueBlock = LLVMValueAsBasicBlock(LLVMGetOperand(termInst, 1));
                        LLVMBasicBlockRef falseBlock = LLVMValueAsBasicBlock(LLVMGetOperand(termInst, 2));

                        // 添加后继基本块
                        successors.computeIfAbsent(bb, k -> new HashSet<>()).add(trueBlock);
                        successors.computeIfAbsent(bb, k -> new HashSet<>()).add(falseBlock);

                        // 添加前驱基本块
                        predecessors.computeIfAbsent(trueBlock, k -> new HashSet<>()).add(bb);
                        predecessors.computeIfAbsent(falseBlock, k -> new HashSet<>()).add(bb);

                        // 记录条件分支
                        List<LLVMBasicBlockRef> targets = new ArrayList<>();
                        targets.add(trueBlock);
                        targets.add(falseBlock);
                        conditionalBranches.put(bb, targets);
                    }
                    // 无条件分支
                    else if (numOperands == 1)
                    {
                        LLVMBasicBlockRef destBlock = LLVMValueAsBasicBlock(LLVMGetOperand(termInst, 0));

                        // 添加后继基本块
                        successors.computeIfAbsent(bb, k -> new HashSet<>()).add(destBlock);

                        // 添加前驱基本块
                        predecessors.computeIfAbsent(destBlock, k -> new HashSet<>()).add(bb);
                    }
                }
            }
        }

        // 收集条件分支中被赋值的变量
        for (Map.Entry<LLVMBasicBlockRef, List<LLVMBasicBlockRef>> entry : conditionalBranches.entrySet())
        {
            for (LLVMBasicBlockRef branchBlock : entry.getValue())
            {
                // 收集这个分支块中所有store指令赋值的变量
                for (LLVMValueRef inst = LLVMGetFirstInstruction(branchBlock); inst != null; inst = LLVMGetNextInstruction(inst))
                {
                    int opcode = LLVMGetInstructionOpcode(inst);
                    if (opcode == LLVMStore)
                    {
                        LLVMValueRef ptr = LLVMGetOperand(inst, 1); // 存储目标
                        String ptrName = LLVMGetValueName(ptr).getString();

                        if(LLVMIsAGlobalVariable(ptr) != null)
                            ptrName = "@" + ptrName;

                        variablesAssignedInBranches.add(ptrName);
//                        System.out.println("在条件分支中发现变量赋值: " + ptrName);
                    }
                }
            }
        }

        // 第二阶段及后续逻辑与原来相似，但需修改load指令处理部分

        // 收集每个基本块中被修改的变量 - 与之前代码保持一致
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
        {
            Set<String> modified = modifiedVars.computeIfAbsent(bb, k -> new HashSet<>());

            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst))
            {
                int opcode = LLVMGetInstructionOpcode(inst);

                if (opcode == LLVMStore)
                {
                    LLVMValueRef ptr = LLVMGetOperand(inst, 1); // 目标地址
                    String ptrName = LLVMGetValueName(ptr).getString();

                    if(LLVMIsAGlobalVariable(ptr) != null)
                        ptrName = "@" + ptrName;

                    // 记录所有被修改的变量，包括全局变量
                    modified.add(ptrName);
                }
            }
        }

        // 标识循环和被修改的变量 - 与之前代码保持一致
        Set<LLVMBasicBlockRef> loopHeaders = new HashSet<>();
        for (Map.Entry<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> entry : predecessors.entrySet())
        {
            for (LLVMBasicBlockRef pred : entry.getValue())
            {
                if (canReach(pred, entry.getKey(), successors))
                {
                    loopHeaders.add(entry.getKey());
                    break;
                }
            }
        }

        // 执行常量传播 - 与之前代码类似，但在处理load指令时添加额外检查
        boolean changed;
        int totalReplacements = 0;

        do
        {
            changed = false;
            constantValues.clear();
            replacements.clear();

            for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
            {
                boolean isLoopHeader = loopHeaders.contains(bb);

                if (isLoopHeader)
                {
                    Set<String> loopModifiedVars = new HashSet<>();
                    collectLoopModifiedVars(bb, loopModifiedVars, modifiedVars, successors, new HashSet<>());

                    for (String var : loopModifiedVars)
                    {
                        constantValues.remove(var);
                    }
                }

                for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst))
                {
                    if (foldedInstructions.contains(inst))
                    {
                        continue;
                    }

                    int opcode = LLVMGetInstructionOpcode(inst);

                    if (opcode == LLVMStore)
                    {
                        // store指令处理 - 与之前相同
                        LLVMValueRef value = LLVMGetOperand(inst, 0);
                        LLVMValueRef ptr = LLVMGetOperand(inst, 1);
                        String ptrName = LLVMGetValueName(ptr).getString();

                        if (LLVMIsAConstant(value) != null)
                        {
                            if(LLVMIsAGlobalVariable(ptr) != null)
                                ptrName = "@" + ptrName;
                            constantValues.put(ptrName, value);
                        }
                        else
                        {
                            if (constantValues.containsKey(LLVMGetValueName(value).getString()))
                            {
                                constantValues.put(ptrName, constantValues.get(LLVMGetValueName(value).getString()));
                            }
                            else
                            {
                                constantValues.remove(ptrName);
                            }
                        }
                    }
                    else if (opcode == LLVMLoad)
                    {
                        // load指令处理 - 修改这部分以检查变量是否在条件分支中被赋值
                        LLVMValueRef ptr = LLVMGetOperand(inst, 0);
                        String ptrName = LLVMGetValueName(ptr).getString();

                        if(LLVMIsAGlobalVariable(ptr) != null)
                            ptrName = "@" + ptrName;

                        boolean inLoop = isInLoop(bb, loopHeaders, predecessors);
                        boolean varModifiedInLoop = false;

                        if (isGlobalVariable(ptrName))
                        {
                            if (inLoop)
                            {
                                varModifiedInLoop = true;
                            }
                            else
                            {
                                varModifiedInLoop = isVarModifiedInLoop(ptrName, bb, modifiedVars, successors);
                            }
                        }
                        else
                        {
                            varModifiedInLoop = isVarModifiedInLoop(ptrName, bb, modifiedVars, successors);
                        }

                        // 新增检查：变量是否在条件分支中被赋值
                        boolean assignedInBranch = variablesAssignedInBranches.contains(ptrName);

                        // 只有在变量不在循环中被修改，且不在条件分支中被赋值时，才进行常量传播
                        if ((!inLoop || !varModifiedInLoop) && !assignedInBranch)
                        {
                            if (constantValues.containsKey(ptrName))
                            {
                                LLVMValueRef constantValue = constantValues.get(ptrName);
                                replacements.put(inst, constantValue);
                                changed = true;
                            }
                        }
                    }

                    // 其他指令处理与之前相同
                    else if (isArithmeticOp(opcode))
                    {
                        // 算术指令：尝试常量折叠
                        boolean allOperandsConstant = true;
                        LLVMValueRef[] operands = new LLVMValueRef[LLVMGetNumOperands(inst)];

                        // 收集所有操作数
                        for (int i = 0; i < operands.length; i++)
                        {
                            operands[i] = LLVMGetOperand(inst, i);
                            // 检查操作数是否是常量或常量表达式结果
                            if (LLVMIsAConstant(operands[i]) == null)
                            {
                                String opName = LLVMGetValueName(operands[i]).getString();
                                if (!constantValues.containsKey(opName))
                                {
                                    allOperandsConstant = false;
                                    break;
                                }
                                else
                                {
                                    operands[i] = constantValues.get(opName);
                                }
                            }
                        }

                        // 尝试进行常量折叠
                        if (allOperandsConstant && operands.length > 0)
                        {
                            LLVMValueRef constResult = foldConstantExpression(inst, operands, opcode);
                            if (constResult != null)
                            {
                                replacements.put(inst, constResult);
                                constantValues.put(LLVMGetValueName(inst).getString(), constResult);
                                changed = true;
                                foldedInstructions.add(inst);
                            }
                        }
                    }
                }
            }

            // 应用替换与之前相同
            for (Map.Entry<LLVMValueRef, LLVMValueRef> entry : replacements.entrySet())
            {
                LLVMValueRef inst = entry.getKey();
                LLVMValueRef constant = entry.getValue();

                LLVMReplaceAllUsesWith(inst, constant);

                if (!foldedInstructions.contains(inst) && LLVMGetFirstUse(inst) == null)
                {
                    LLVMInstructionEraseFromParent(inst);
                }
            }

            totalReplacements += replacements.size();

        } while (changed);

//        System.out.println("优化函数 " + LLVMGetValueName(func).getString() +
//            "，常量传播执行了 " + totalReplacements + " 次替换");
    }

    /**
     * 检查一个基本块是否可以到达另一个基本块
     */
    private boolean canReach(LLVMBasicBlockRef from, LLVMBasicBlockRef to,
                             Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> successors)
    {
        if (from == to) return true;

        Set<LLVMBasicBlockRef> visited = new HashSet<>();
        Queue<LLVMBasicBlockRef> queue = new LinkedList<>();
        queue.add(from);

        while (!queue.isEmpty())
        {
            LLVMBasicBlockRef current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            Set<LLVMBasicBlockRef> succs = successors.get(current);
            if (succs != null)
            {
                for (LLVMBasicBlockRef succ : succs)
                {
                    if (succ == to) return true;
                    queue.add(succ);
                }
            }
        }

        return false;
    }

    /**
     * 收集循环中所有被修改的变量
     */
    private void collectLoopModifiedVars(LLVMBasicBlockRef head, Set<String> result,
                                         Map<LLVMBasicBlockRef, Set<String>> modifiedVars,
                                         Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> successors,
                                         Set<LLVMBasicBlockRef> visited)
    {
        if (visited.contains(head)) return;
        visited.add(head);

        // 添加当前基本块修改的变量
        Set<String> modified = modifiedVars.get(head);
        if (modified != null)
        {
            result.addAll(modified);
        }

        // 递归处理后继基本块，直到遇到回边
        Set<LLVMBasicBlockRef> succs = successors.get(head);
        if (succs != null)
        {
            for (LLVMBasicBlockRef succ : succs)
            {
                collectLoopModifiedVars(succ, result, modifiedVars, successors, visited);
            }
        }
    }

    /**
     * 检查基本块是否在循环内
     */
    private boolean isInLoop(LLVMBasicBlockRef bb, Set<LLVMBasicBlockRef> loopHeaders,
                             Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> predecessors)
    {
        // 简单实现：如果基本块有前驱且前驱是循环头，则认为它在循环内
        Set<LLVMBasicBlockRef> preds = predecessors.get(bb);
        if (preds != null)
        {
            for (LLVMBasicBlockRef pred : preds)
            {
                if (loopHeaders.contains(pred) || isInLoop(pred, loopHeaders, predecessors))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检查变量是否在循环中被修改
     */
    private boolean isVarModifiedInLoop(String varName, LLVMBasicBlockRef bb,
                                        Map<LLVMBasicBlockRef, Set<String>> modifiedVars,
                                        Map<LLVMBasicBlockRef, Set<LLVMBasicBlockRef>> successors)
    {
        Set<LLVMBasicBlockRef> visited = new HashSet<>();
        Queue<LLVMBasicBlockRef> queue = new LinkedList<>();
        queue.add(bb);

        while (!queue.isEmpty())
        {
            LLVMBasicBlockRef current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            // 检查当前基本块是否修改了变量
            Set<String> modified = modifiedVars.get(current);
            if (modified != null && modified.contains(varName))
            {
                return true;
            }

            // 添加后继基本块到队列
            Set<LLVMBasicBlockRef> succs = successors.get(current);
            if (succs != null)
            {
                queue.addAll(succs);
            }
        }

        return false;
    }

    /**
     * 修复全局变量更新问题
     * 确保对全局变量的修改会被正确地存回全局变量
     *
     * @param func 待优化的函数
     */
    private void fixGlobalVariableUpdates(LLVMValueRef func)
    {
        // 不仅存储加载指令，还需存储加载指令的结果值
        Map<String, LLVMValueRef> globalLoads = new HashMap<>(); // 全局变量名 -> load指令（即结果值）

        // 存储算术指令对全局变量的修改
        Map<String, LLVMValueRef> globalModified = new HashMap<>();

        // 需要插入store指令的信息
        List<StoreInsertionPoint> storeInsertions = new ArrayList<>();

//        System.out.println("开始检查函数 " + LLVMGetValueName(func).getString() + " 的全局变量更新");

        // 遍历所有基本块和指令
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
        {
            // 每个基本块开始时清空状态
            globalLoads.clear();
            globalModified.clear();

            LLVMValueRef lastInst = null;
            LLVMValueRef prevInst = null;

            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst))
            {

                prevInst = lastInst;
                lastInst = inst;
                int opcode = LLVMGetInstructionOpcode(inst);

                if (opcode == LLVMLoad)
                {
                    // 检查是否从全局变量加载
                    LLVMValueRef ptr = LLVMGetOperand(inst, 0);
                    String ptrName = LLVMGetValueName(ptr).getString();

                    if (isGlobalVariable(ptrName))
                    {
                        // 记录加载指令作为该全局变量的值
                        globalLoads.put(normalizeGlobalName(ptrName), inst);
//                        System.out.println("  检测到全局变量加载: " + ptrName);
                    }
                }
                // 对加载后的值进行算术操作
                else if (isArithmeticOp(opcode))
                {
                    boolean usesGlobalVar = false;
                    String globalVarUsed = null;

                    // 检查操作数是否包含全局变量的加载结果
                    for (int i = 0; i < LLVMGetNumOperands(inst); i++)
                    {
                        LLVMValueRef operand = LLVMGetOperand(inst, i);

                        // 检查所有已加载的全局变量
                        for (Map.Entry<String, LLVMValueRef> entry : globalLoads.entrySet())
                        {
                            if (operand == entry.getValue())
                            {
                                usesGlobalVar = true;
                                globalVarUsed = entry.getKey();
                                break;
                            }
                        }

                        if (usesGlobalVar)
                            break;
                    }

                    if (usesGlobalVar)
                    {
                        // 标记此全局变量被修改，并记录修改它的指令
                        globalModified.put(globalVarUsed, inst);
//                        System.out.println("  检测到全局变量修改: " + globalVarUsed);
                    }
                }

                // 处理函数调用、分支或基本块结束前的情况
                boolean isCallOrTerminator = opcode == LLVMCall || LLVMIsATerminatorInst(inst) != null;

                if (isCallOrTerminator && prevInst != null)
                {
                    // 在函数调用或终结指令前，插入全局变量更新
                    for (Map.Entry<String, LLVMValueRef> entry : globalModified.entrySet())
                    {
                        String globalName = entry.getKey();
                        LLVMValueRef valueInst = entry.getValue();
                        LLVMValueRef globalPtr = findGlobalVariable(globalName);

                        if (globalPtr != null)
                        {
                            // 创建存储插入点
                            storeInsertions.add(new StoreInsertionPoint(bb, inst, globalPtr, valueInst));
//                            System.out.println("  计划在指令前插入全局变量 " + globalName + " 的存储");
                        }
                    }

                    // 如果是函数调用，清除全局变量状态
                    if (opcode == LLVMCall)
                    {
                        globalLoads.clear();
                        globalModified.clear();
                    }
                }
            }

            // 在基本块结束时，为所有修改过但未存储的全局变量添加store指令
            if (lastInst != null && !globalModified.isEmpty())
            {
                for (Map.Entry<String, LLVMValueRef> entry : globalModified.entrySet())
                {
                    String globalName = entry.getKey();
                    LLVMValueRef valueInst = entry.getValue();
                    LLVMValueRef globalPtr = findGlobalVariable(globalName);

                    if (globalPtr != null)
                    {
                        storeInsertions.add(new StoreInsertionPoint(bb, lastInst, globalPtr, valueInst));
//                        System.out.println("  计划在基本块结束前插入全局变量 " + globalName + " 的存储");
                    }
                }
            }
        }

        // 执行所有store指令插入
        int insertedStores = 0;
        for (StoreInsertionPoint point : storeInsertions)
        {
            LLVMBuilderRef builder = LLVMCreateBuilder();
            LLVMPositionBuilderBefore(builder, point.insertBefore); // 在指令之前插入
            LLVMBuildStore(builder, point.value, point.globalPtr);
            LLVMDisposeBuilder(builder);
            insertedStores++;
        }

//        System.out.println("优化函数 " + LLVMGetValueName(func).getString() +
//            "，修复全局变量更新，插入了 " + insertedStores + " 个store指令");
    }

    /**
     * 标准化全局变量名（去除@前缀）
     */
    private String normalizeGlobalName(String name)
    {
        return name.startsWith("@") ? name.substring(1) : name;
    }

    /**
     * 在指定位置前插入store指令，用于更新全局变量
     */
    private void insertStoreInstructions(LLVMBasicBlockRef bb, LLVMValueRef insertBefore,
                                         Map<String, LLVMValueRef> globalModified,
                                         List<StoreInsertionPoint> storeInsertions)
    {
        for (Map.Entry<String, LLVMValueRef> entry : globalModified.entrySet())
        {
            String globalName = entry.getKey();
            LLVMValueRef value = entry.getValue();

            LLVMValueRef globalPtr = findGlobalVariable(globalName);
            if (globalPtr != null)
            {
                storeInsertions.add(new StoreInsertionPoint(bb, insertBefore, globalPtr, value));
            }
        }
    }

    /**
     * 判断是否是全局变量
     */
    private boolean isGlobalVariable(String name)
    {
        String normalizedName = normalizeGlobalName(name);
        return globalVariables.contains(normalizedName);
    }

    /**
     * 查找全局变量
     */
    private LLVMValueRef findGlobalVariable(String name)
    {
        String normalizedName = normalizeGlobalName(name);

        for (LLVMValueRef global = LLVMGetFirstGlobal(module); global != null; global = LLVMGetNextGlobal(global))
        {
            String globalName = LLVMGetValueName(global).getString();
            if (globalName.equals(normalizedName))
            {
                return global;
            }
        }
        return null;
    }

    /**
     * 判断是否是算术操作
     *
     * @param opcode 操作码
     * @return 是否是算术操作
     */
    private boolean isArithmeticOp(int opcode)
    {
        return opcode == LLVMAdd || opcode == LLVMFAdd ||
            opcode == LLVMSub || opcode == LLVMFSub ||
            opcode == LLVMMul || opcode == LLVMFMul ||
            opcode == LLVMUDiv || opcode == LLVMSDiv || opcode == LLVMFDiv ||
            opcode == LLVMURem || opcode == LLVMSRem || opcode == LLVMFRem;
    }

    /**
     * 尝试折叠常量表达式
     * 注意：这是一个简化的实现，只处理一些基本情况
     *
     * @param inst     指令
     * @param operands 操作数
     * @param opcode   操作码
     * @return 折叠结果，如果无法折叠则返回null
     */
    private LLVMValueRef foldConstantExpression(LLVMValueRef inst, LLVMValueRef[] operands, int opcode)
    {
        // 简化实现，只处理整数常量
        // 实际上需要更复杂的实现来处理各种类型和操作
        try
        {
            if (operands.length < 2)
                return null;

            // 尝试获取常量的整数值
            long value1 = LLVMConstIntGetSExtValue(operands[0]);
            long value2 = LLVMConstIntGetSExtValue(operands[1]);

            long result = 0;
            boolean valid = true;

            switch (opcode)
            {
                case LLVMAdd:
                    result = value1 + value2;
                    break;
                case LLVMSub:
                    result = value1 - value2;
                    break;
                case LLVMMul:
                    result = value1 * value2;
                    break;
                case LLVMSDiv:
                    if (value2 == 0)
                    {
                        valid = false;
                    }
                    else
                    {
                        result = value1 / value2;
                    }
                    break;
                case LLVMSRem:
                    if (value2 == 0)
                    {
                        valid = false;
                    }
                    else
                    {
                        result = value1 % value2;
                    }
                    break;
                default:
                    valid = false;
            }

            if (valid)
            {
                // 创建新的常量值
                LLVMTypeRef type = LLVMTypeOf(inst);
                return LLVMConstInt(type, result, 1); // 带符号整数
            }
        }
        catch (Exception e)
        {
            // 处理可能的异常，如不支持的操作数类型
        }

        return null;
    }

    /**
     * 检查是否有store和load之间对同一内存位置的写入
     * 这个函数在完整实现中应该检查可能的冲突
     *
     * @param storeInst store指令
     * @param loadInst  load指令
     * @return 如果没有冲突写入返回true
     */
    private boolean isSafeToReplace(LLVMValueRef storeInst, LLVMValueRef loadInst)
    {
        // 在完整版本中，我们需要检查storeInst和loadInst之间的所有指令
        // 查看是否有其他写入相同内存位置的操作

        // 简化版本：假设安全
        return true;
    }

    /**
     * 优化冗余分支条件
     * 消除形如 "icmp -> zext -> icmp ne 0" 的模式
     *
     * @param func 待优化的函数
     */
    private void simplifyBranchConditions(LLVMValueRef func)
    {
        // 追踪所有的icmp指令和它们的结果
        Map<String, LLVMValueRef> icmpResults = new HashMap<>();

        // 需要替换的指令及其对应的原始icmp结果
        Map<LLVMValueRef, LLVMValueRef> replacements = new HashMap<>();

        // 记录zext指令和它们的源操作数
        Map<String, String> zextSources = new HashMap<>();

        // 第一次遍历：识别所有的icmp和zext指令
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
        {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst))
            {
                int opcode = LLVMGetInstructionOpcode(inst);

                // 处理icmp指令
                if (opcode == LLVMICmp)
                {
                    String resultName = LLVMGetValueName(inst).getString();
                    icmpResults.put(resultName, inst);
                }
                // 处理zext指令
                else if (opcode == LLVMZExt)
                {
                    if (LLVMGetNumOperands(inst) > 0)
                    {
                        LLVMValueRef source = LLVMGetOperand(inst, 0);
                        String sourceName = LLVMGetValueName(source).getString();
                        String resultName = LLVMGetValueName(inst).getString();

                        // 记录zext的源操作数
                        zextSources.put(resultName, sourceName);
                    }
                }
            }
        }

        // 第二次遍历：寻找 "zext -> icmp ne 0" 模式
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
        {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst))
            {
                int opcode = LLVMGetInstructionOpcode(inst);

                // 检查是否是icmp ne指令
                if (opcode == LLVMICmp && LLVMGetICmpPredicate(inst) == LLVMIntNE)
                {
                    if (LLVMGetNumOperands(inst) >= 2)
                    {
                        LLVMValueRef op1 = LLVMGetOperand(inst, 0);
                        LLVMValueRef op2 = LLVMGetOperand(inst, 1);

                        // 检查第二个操作数是否为常量0，第一个操作数是否来自zext
                        if (LLVMIsAConstantInt(op2) != null && LLVMConstIntGetSExtValue(op2) == 0)
                        {
                            String op1Name = LLVMGetValueName(op1).getString();

                            // 如果op1是zext的结果
                            if (zextSources.containsKey(op1Name))
                            {
                                String originalIcmpName = zextSources.get(op1Name);

                                // 检查zext的源是否是icmp结果
                                if (icmpResults.containsKey(originalIcmpName))
                                {
                                    // 找到冗余模式，标记为可替换
                                    replacements.put(inst, icmpResults.get(originalIcmpName));
                                }
                            }
                        }
                    }
                }
            }
        }

        // 执行替换
        for (Map.Entry<LLVMValueRef, LLVMValueRef> entry : replacements.entrySet())
        {
            LLVMValueRef redundantInst = entry.getKey();
            LLVMValueRef originalIcmp = entry.getValue();

            // 用原始的icmp指令替换冗余的icmp ne指令
            LLVMReplaceAllUsesWith(redundantInst, originalIcmp);

            // 删除冗余指令
            LLVMInstructionEraseFromParent(redundantInst);
        }

//        System.out.println("优化函数 " + LLVMGetValueName(func).getString() +
//            "，简化了 " + replacements.size() + " 个冗余分支条件");
    }

    /**
     * 用于存储需要插入的store指令信息
     */
    private static class StoreInsertionPoint
    {
        LLVMBasicBlockRef block;
        LLVMValueRef insertBefore;
        LLVMValueRef globalPtr;
        LLVMValueRef value;

        public StoreInsertionPoint(LLVMBasicBlockRef block, LLVMValueRef insertBefore,
                                   LLVMValueRef globalPtr, LLVMValueRef value)
        {
            this.block = block;
            this.insertBefore = insertBefore;
            this.globalPtr = globalPtr;
            this.value = value;
        }
    }
}
