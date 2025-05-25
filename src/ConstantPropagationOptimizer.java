import org.bytedeco.llvm.LLVM.*;
import java.util.*;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * 常量传播优化器类
 * 实现基于工作列表算法的常量传播优化
 */
public class ConstantPropagationOptimizer {

    // 数据流值的枚举
    private enum Value {
        UNDEF, // 未初始化
        NAC // Not a Constant
        // 常量值由Integer表示
    }

    // 存储数据流值（可能是UNDEF, NAC或常量值）
    private static class FlowValue {
        private Value type;
        private Integer constValue;

        private FlowValue(Value type) {
            this.type = type;
            this.constValue = null;
        }

        private FlowValue(Integer constValue) {
            this.type = null;
            this.constValue = constValue;
        }

        public static FlowValue undef() {
            return new FlowValue(Value.UNDEF);
        }

        public static FlowValue nac() {
            return new FlowValue(Value.NAC);
        }

        public static FlowValue constant(int value) {
            return new FlowValue(value);
        }

        public boolean isUndef() {
            return type == Value.UNDEF;
        }

        public boolean isNac() {
            return type == Value.NAC;
        }

        public boolean isConstant() {
            return type == null && constValue != null;
        }

        public Integer getConstValue() {
            return constValue;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof FlowValue))
                return false;
            FlowValue other = (FlowValue) obj;

            if (this.isConstant() && other.isConstant()) {
                return this.constValue.equals(other.constValue);
            }

            return this.type == other.type;
        }
    }

    private static class InstructionState
    {
        // 指令的输入值映射（变量名 -> FlowValue）
        private Map<String, FlowValue> inValues;
        // 指令的输出值（如果有）
        private Map<String, FlowValue> outValue;
        // 指令引用的变量名列表（输入）
        private Set<String> uses;
        // 指令定义的变量名（输出，如果有）
        private String def;

        public InstructionState()
        {
            this.inValues = new HashMap<>();
            this.outValue = new HashMap<>();
            this.uses = new HashSet<>();
            this.def = null;
        }

        public void addUse(String varName)
        {
            uses.add(varName);
        }

        public void setDef(String varName)
        {
            this.def = varName;
        }

        public Set<String> getUses()
        {
            return uses;
        }

        public String getDef()
        {
            return def;
        }

        public void setInValue(String varName, FlowValue value)
        {
            inValues.put(varName, value);
        }

        public Map<String, FlowValue> getInValue(String varName)
        {
            return inValues;
        }

        public void setOutValue(String varName, FlowValue value)
        {
            outValue.put(varName, value);
        }

        public Map<String, FlowValue> getOutValue()
        {
            return outValue;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (!(obj instanceof InstructionState))
                return false;
            InstructionState other = (InstructionState) obj;

            // 比较输入值映射
            if (!this.inValues.equals(other.inValues))
                return false;

            // 比较输出值
            return this.outValue.equals(other.outValue);
        }
    }

    private LLVMModuleRef module;
    private Set<String> globalVariables = new HashSet<>();
    private Map<String, FlowValue> memoryValues = new HashMap<>();

    // 添加这两个全局映射
    private Map<String, LLVMValueRef> instructionStringToValue = new HashMap<>();
    private Map<LLVMValueRef, String> instructionValueToString = new HashMap<>();

    private void recordAllInstructions()
    {
        instructionStringToValue.clear();
        instructionValueToString.clear();

        // 遍历所有函数
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func))
        {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0)
            {
                // 遍历所有基本块
                for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
                {
                    // 遍历所有指令
                    for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst))
                    {
                        String instStr = LLVMPrintValueToString(inst).getString();
                        instructionStringToValue.put(instStr, inst);
                        instructionValueToString.put(inst, instStr);
                    }
                }
            }
        }
    }
    /**
     * 构造函数
     *
     * @param module LLVM模块
     */
    public ConstantPropagationOptimizer(LLVMModuleRef module) {
        this.module = module;
        collectGlobalVariables();
        recordAllInstructions();
    }

    /**
     * 收集模块中的所有全局变量
     */
    private void collectGlobalVariables() {
        globalVariables.clear();
        for (LLVMValueRef global = LLVMGetFirstGlobal(module); global != null; global = LLVMGetNextGlobal(global)) {
            String name = LLVMGetValueName(global).getString();
            globalVariables.add("@" + name);
        }
    }

    /**
     * 运行常量传播优化
     *
     * @return 优化后的模块
     */
    public LLVMModuleRef run() {
        boolean changed = false;

        // 对每个函数进行常量传播优化
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                changed |= propagateConstants(func);
            }
        }

        return module;
    }

    /**
     * 在单个函数中执行常量传播
     *
     * @param func 待优化的函数
     * @return 如果函数被修改，返回true
     */
    private boolean propagateConstants(LLVMValueRef func) {
        // 构建控制流图
        memoryValues.clear();

        Map<LLVMValueRef, List<LLVMValueRef>> successors = buildCFG(func);
        Map<LLVMValueRef, List<LLVMValueRef>> predecessors = buildPredecessors(successors);

        // 存储指令的数据流值
        Map<LLVMValueRef, FlowValue> values = new HashMap<>();

        // 初始化工作列表和指令的in和out值
        Queue<LLVMValueRef> worklist = new LinkedList<>();
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                values.put(inst, FlowValue.undef());
                worklist.add(inst);
            }
        }

        // 处理全局变量初始值
        Map<String, Integer> globalInitValues = collectGlobalInitValues();

        // 执行工作列表算法
        while (!worklist.isEmpty()) {
            LLVMValueRef inst = worklist.poll();
            String string = LLVMPrintValueToString(inst).getString();
            String string_succ;
            FlowValue oldValue = values.get(inst);

            // 计算新的数据流值
            FlowValue newValue = transfer(inst, values, globalInitValues);

            // 如果值发生变化，更新并将后继加入工作列表
            if (!newValue.equals(oldValue)) {
                values.put(inst, newValue);

                List<LLVMValueRef> succs = successors.getOrDefault(inst, Collections.emptyList());
                if(!succs.isEmpty())
                    string_succ = LLVMPrintValueToString(succs.get(0)).getString();

                worklist.addAll(succs);
            }
        }

        // 根据分析结果优化代码
        return applyOptimization(func, values);
    }

    /**
     * 构建控制流图
     *
     * @param func 函数
     * @return 指令的后继映射
     */
    private Map<LLVMValueRef, List<LLVMValueRef>> buildCFG(LLVMValueRef func) {
        Map<LLVMValueRef, List<LLVMValueRef>> successors = new HashMap<>();
        Map<LLVMBasicBlockRef, LLVMValueRef> firstInstInBlock = new HashMap<>();
        Map<LLVMBasicBlockRef, List<LLVMValueRef>> blockSuccessors = new HashMap<>();

        // 记录每个基本块的第一条指令
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            LLVMValueRef firstInst = LLVMGetFirstInstruction(bb);
            if (firstInst != null) {
                String str_now = LLVMPrintValueToString(firstInst).getString();
                firstInstInBlock.put(bb, firstInst);
            }

            // 初始化基本块后继列表
            blockSuccessors.put(bb, new ArrayList<>());
        }

        // 构建基本块之间的后继关系
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            LLVMValueRef terminator = LLVMGetBasicBlockTerminator(bb);
            if (terminator != null) {
                int opcode = LLVMGetInstructionOpcode(terminator);

                if (opcode == LLVMBr) {
                    // 处理分支指令
                    int numOperands = LLVMGetNumOperands(terminator);

                    if (numOperands == 3) { // 条件分支
                        LLVMBasicBlockRef trueBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 2));
                        LLVMBasicBlockRef falseBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 1));

                        if (firstInstInBlock.containsKey(trueBlock)) {
                            blockSuccessors.get(bb).add(firstInstInBlock.get(trueBlock));
                            // 将true分支添加到terminator的后继
                            LLVMValueRef trueFirst = firstInstInBlock.get(trueBlock);
                            String str_now = LLVMPrintValueToString(trueFirst).getString();
                            successors.computeIfAbsent(terminator, k -> new ArrayList<>())
                                    .add(trueFirst);
                        }
                        if (firstInstInBlock.containsKey(falseBlock)) {
                            blockSuccessors.get(bb).add(firstInstInBlock.get(falseBlock));
                            // 将false分支添加到terminator的后继
                            LLVMValueRef falseFirst = firstInstInBlock.get(falseBlock);
                            String str_now = LLVMPrintValueToString(falseFirst).getString();
                            successors.computeIfAbsent(terminator, k -> new ArrayList<>())
                                    .add(falseFirst);
                        }
                    } else if (numOperands == 1) { // 无条件分支
                        LLVMBasicBlockRef destBlock = LLVMValueAsBasicBlock(LLVMGetOperand(terminator, 0));
                        if (firstInstInBlock.containsKey(destBlock)) {
                            blockSuccessors.get(bb).add(firstInstInBlock.get(destBlock));
                            // 将目标添加到terminator的后继
                            LLVMValueRef destFirst = firstInstInBlock.get(destBlock);
                            String str_now = LLVMPrintValueToString(destFirst).getString();
                            successors.computeIfAbsent(terminator, k -> new ArrayList<>())
                                    .add(destFirst);
                            successors.computeIfAbsent(terminator, k -> new ArrayList<>())
                                    .add(destFirst);
                        }
                    }
                } else if (opcode == LLVMRet) {
                    // 返回指令没有后继，所以不需要添加
//                    successors.computeIfAbsent(terminator, k -> new ArrayList<>());
                }
            }
        }

        // 构建指令级别的后继关系
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            LLVMValueRef prev = null;

            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                String str_now = LLVMPrintValueToString(inst).getString();
                String str_prev;
                if (prev != null) {
                    str_prev = LLVMPrintValueToString(prev).getString();
                    successors.computeIfAbsent(prev, k -> new ArrayList<>()).add(inst);
                }
                prev = inst;
            }

            // 如果是基本块的最后一条指令，连接到后继基本块
            if (prev != null) {
                int opcode = LLVMGetInstructionOpcode(prev);
                if (opcode == LLVMBr || opcode == LLVMRet) {
                    // 已经在上面处理过终结指令
                } else {
                    // 非终结指令连接到后继基本块
                    for (LLVMValueRef succ : blockSuccessors.get(bb)) {
                        successors.computeIfAbsent(prev, k -> new ArrayList<>()).add(succ);
                    }
                }
            }
        }

        return successors;
    }

    /**
     * 构建前驱映射
     *
     * @param successors 后继映射
     * @return 前驱映射
     */
    private Map<LLVMValueRef, List<LLVMValueRef>> buildPredecessors(Map<LLVMValueRef, List<LLVMValueRef>> successors) {
        Map<LLVMValueRef, List<LLVMValueRef>> predecessors = new HashMap<>();

        for (Map.Entry<LLVMValueRef, List<LLVMValueRef>> entry : successors.entrySet()) {
            LLVMValueRef from = entry.getKey();
            for (LLVMValueRef to : entry.getValue()) {
                predecessors.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
            }
        }

        return predecessors;
    }

    /**
     * 构建更精确的控制流图
     * 基于数据依赖关系而非简单的指令顺序
     */
    private Map<LLVMValueRef, List<LLVMValueRef>> buildPreciseCFG(LLVMValueRef func)
    {
        // 指令到其前驱和后继的映射
        Map<LLVMValueRef, List<LLVMValueRef>> predecessors = new HashMap<>();
        Map<LLVMValueRef, List<LLVMValueRef>> successors = new HashMap<>();

        // 变量名到最后定义该变量的指令的映射
        Map<String, LLVMValueRef> lastDefMap = new HashMap<>();

        // 每个基本块的终结指令
        Map<LLVMBasicBlockRef, LLVMValueRef> blockTerminators = new HashMap<>();

        // 每个基本块的第一条指令
        Map<LLVMBasicBlockRef, LLVMValueRef> blockFirstInsts = new HashMap<>();

        // 指令状态映射
        Map<LLVMValueRef, InstructionState> instStates = new HashMap<>();

        // 第一遍：分析每条指令的def和use，收集基本块信息
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
        {
            LLVMValueRef firstInst = null;
            LLVMValueRef terminator = null;

            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst))
            {
                if (firstInst == null)
                {
                    firstInst = inst;
                }

                // 创建指令状态
                InstructionState state = new InstructionState();
                instStates.put(inst, state);

                // 分析指令类型
                int opcode = LLVMGetInstructionOpcode(inst);

                switch (opcode)
                {
                    case LLVMAlloca:
                        // alloca定义了一个变量
                        String allocaName = LLVMGetValueName(inst).getString();
                        state.setDef(allocaName);
                        lastDefMap.put(allocaName, inst);
                        break;

                    case LLVMLoad:
                        // load使用了指针变量，定义了结果变量
                        String loadResult = LLVMGetValueName(inst).getString();
                        LLVMValueRef loadPtr = LLVMGetOperand(inst, 0);
                        String loadPtrName = LLVMGetValueName(loadPtr).getString();

                        state.addUse(loadPtrName);
                        state.setDef(loadResult);
                        lastDefMap.put(loadResult, inst);
                        break;

                    case LLVMStore:
                        // store使用了值和指针变量
                        LLVMValueRef storeValue = LLVMGetOperand(inst, 0);
                        LLVMValueRef storePtr = LLVMGetOperand(inst, 1);

                        // 如果值是变量（不是常量）
                        if (LLVMIsAConstant(storeValue) == null)
                        {
                            String storeValueName = LLVMGetValueName(storeValue).getString();
                            state.addUse(storeValueName);
                        }

                        String storePtrName = LLVMGetValueName(storePtr).getString();
                        state.addUse(storePtrName);

                        // store不定义变量，但会更新内存
                        break;

                    case LLVMAdd:
                    case LLVMSub:
                    case LLVMMul:
                    case LLVMSDiv:
                    case LLVMSRem:
                        // 二元操作使用两个操作数，定义结果
                        String binaryResult = LLVMGetValueName(inst).getString();
                        LLVMValueRef lhs = LLVMGetOperand(inst, 0);
                        LLVMValueRef rhs = LLVMGetOperand(inst, 1);

                        // 如果操作数是变量（不是常量）
                        if (LLVMIsAConstant(lhs) == null)
                        {
                            String lhsName = LLVMGetValueName(lhs).getString();
                            state.addUse(lhsName);
                        }

                        if (LLVMIsAConstant(rhs) == null)
                        {
                            String rhsName = LLVMGetValueName(rhs).getString();
                            state.addUse(rhsName);
                        }

                        state.setDef(binaryResult);
                        lastDefMap.put(binaryResult, inst);
                        break;

                    case LLVMICmp:
                        // 比较指令使用两个操作数，定义结果
                        String cmpResult = LLVMGetValueName(inst).getString();
                        LLVMValueRef cmpLhs = LLVMGetOperand(inst, 0);
                        LLVMValueRef cmpRhs = LLVMGetOperand(inst, 1);

                        if (LLVMIsAConstant(cmpLhs) == null)
                        {
                            String cmpLhsName = LLVMGetValueName(cmpLhs).getString();
                            state.addUse(cmpLhsName);
                        }

                        if (LLVMIsAConstant(cmpRhs) == null)
                        {
                            String cmpRhsName = LLVMGetValueName(cmpRhs).getString();
                            state.addUse(cmpRhsName);
                        }

                        state.setDef(cmpResult);
                        lastDefMap.put(cmpResult, inst);
                        break;

                    case LLVMBr:
                        // 分支指令可能使用条件变量
                        int numOperands = LLVMGetNumOperands(inst);
                        if (numOperands == 3)
                        { // 条件分支
                            LLVMValueRef condition = LLVMGetOperand(inst, 0);
                            if (LLVMIsAConstant(condition) == null)
                            {
                                String condName = LLVMGetValueName(condition).getString();
                                state.addUse(condName);
                            }
                        }
                        terminator = inst;
                        break;

                    case LLVMRet:
                        // 返回指令可能使用返回值
                        if (LLVMGetNumOperands(inst) > 0)
                        {
                            LLVMValueRef retVal = LLVMGetOperand(inst, 0);
                            if (LLVMIsAConstant(retVal) == null)
                            {
                                String retValName = LLVMGetValueName(retVal).getString();
                                state.addUse(retValName);
                            }
                        }
                        terminator = inst;
                        break;

                    case LLVMZExt:
                    case LLVMSExt:
                        // 扩展指令使用一个操作数，定义结果
                        String extResult = LLVMGetValueName(inst).getString();
                        LLVMValueRef extOp = LLVMGetOperand(inst, 0);

                        if (LLVMIsAConstant(extOp) == null)
                        {
                            String extOpName = LLVMGetValueName(extOp).getString();
                            state.addUse(extOpName);
                        }

                        state.setDef(extResult);
                        lastDefMap.put(extResult, inst);
                        break;

                    default:
                        // 处理其他类型的指令...
                        break;
                }
            }

            if (firstInst != null)
            {
                blockFirstInsts.put(bb, firstInst);
            }

            if (terminator != null)
            {
                blockTerminators.put(bb, terminator);
            }
        }

        // 第二遍：建立指令间的前驱后继关系
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb))
        {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst))
            {
                InstructionState state = instStates.get(inst);

                // 对于每个使用的变量，找到它的最后定义点，建立依赖关系
                for (String useName : state.getUses())
                {
                    if (lastDefMap.containsKey(useName))
                    {
                        LLVMValueRef defInst = lastDefMap.get(useName);

                        // 将定义指令添加为当前指令的前驱
                        predecessors.computeIfAbsent(inst, k -> new ArrayList<>()).add(defInst);
                        // 将当前指令添加为定义指令的后继
                        successors.computeIfAbsent(defInst, k -> new ArrayList<>()).add(inst);
                    }
                }

                // 如果这是基本块的终结指令，连接到目标块的第一条指令
                if (blockTerminators.containsValue(inst))
                {
                    int opcode = LLVMGetInstructionOpcode(inst);

                    if (opcode == LLVMBr)
                    {
                        int numOperands = LLVMGetNumOperands(inst);

                        if (numOperands == 3)
                        { // 条件分支
                            LLVMBasicBlockRef trueBlock = LLVMValueAsBasicBlock(LLVMGetOperand(inst, 2));
                            LLVMBasicBlockRef falseBlock = LLVMValueAsBasicBlock(LLVMGetOperand(inst, 1));

                            if (blockFirstInsts.containsKey(trueBlock))
                            {
                                LLVMValueRef trueFirst = blockFirstInsts.get(trueBlock);
                                successors.computeIfAbsent(inst, k -> new ArrayList<>()).add(trueFirst);
                                predecessors.computeIfAbsent(trueFirst, k -> new ArrayList<>()).add(inst);
                            }

                            if (blockFirstInsts.containsKey(falseBlock))
                            {
                                LLVMValueRef falseFirst = blockFirstInsts.get(falseBlock);
                                successors.computeIfAbsent(inst, k -> new ArrayList<>()).add(falseFirst);
                                predecessors.computeIfAbsent(falseFirst, k -> new ArrayList<>()).add(inst);
                            }
                        }
                        else if (numOperands == 1)
                        { // 无条件分支
                            LLVMBasicBlockRef destBlock = LLVMValueAsBasicBlock(LLVMGetOperand(inst, 0));

                            if (blockFirstInsts.containsKey(destBlock))
                            {
                                LLVMValueRef destFirst = blockFirstInsts.get(destBlock);
                                successors.computeIfAbsent(inst, k -> new ArrayList<>()).add(destFirst);
                                predecessors.computeIfAbsent(destFirst, k -> new ArrayList<>()).add(inst);
                            }
                        }
                    }
                    // 返回指令没有后继
                }
                // 所有非终结指令都要连接到块内的下一条指令
                else if (LLVMGetNextInstruction(inst) != null)
                {
                    LLVMValueRef nextInst = LLVMGetNextInstruction(inst);
                    successors.computeIfAbsent(inst, k -> new ArrayList<>()).add(nextInst);
                    predecessors.computeIfAbsent(nextInst, k -> new ArrayList<>()).add(inst);
                }
            }
        }

        return successors;
    }

    /**
     * 收集全局变量的初始值
     *
     * @return 全局变量名到初始值的映射
     */
    private Map<String, Integer> collectGlobalInitValues() {
        Map<String, Integer> result = new HashMap<>();

        for (LLVMValueRef global = LLVMGetFirstGlobal(module); global != null; global = LLVMGetNextGlobal(global)) {
            String name = "@" + LLVMGetValueName(global).getString();
            LLVMValueRef initializer = LLVMGetInitializer(global);

            if (initializer != null && LLVMIsAConstantInt(initializer) != null) {
                long value = LLVMConstIntGetSExtValue(initializer);
                result.put(name, (int) value);
            }
        }

        return result;
    }

    /**
     * 传递函数 - 计算指令的数据流值
     *
     * @param inst             当前指令
     * @param values           已知的指令值映射
     * @param globalInitValues 全局变量初始值
     * @return 指令的新数据流值
     */
    private FlowValue transfer(LLVMValueRef inst, Map<LLVMValueRef, FlowValue> values,
            Map<String, Integer> globalInitValues) {
        int opcode = LLVMGetInstructionOpcode(inst);

        switch (opcode) {
            case LLVMAdd:
            case LLVMSub:
            case LLVMMul:
            case LLVMSDiv:
            case LLVMSRem:
                return evaluateBinaryOp(inst, opcode, values);

            case LLVMLoad:
                return evaluateLoad(inst, values, globalInitValues);

            case LLVMAlloca:
                // 分配指令本身不产生值
                return FlowValue.undef();

            case LLVMStore:
                // 存储指令不生成值
                //todo 这里应该接入伪代码里v = n case
                return evaluateStore(inst,values,globalInitValues);

            case LLVMICmp:
                return evaluateICmp(inst, values);

            case LLVMZExt:
            case LLVMSExt:
                return evaluateExtension(inst, values);

            default:
                // 对于其他指令，默认为NAC
                return FlowValue.nac();
        }
    }

    /**
     * 评估二元操作
     */
    private FlowValue evaluateBinaryOp(LLVMValueRef inst, int opcode, Map<LLVMValueRef, FlowValue> values) {
        LLVMValueRef lhs = LLVMGetOperand(inst, 0);
        LLVMValueRef rhs = LLVMGetOperand(inst, 1);

        FlowValue lhsValue = getValueOf(lhs, values);
        FlowValue rhsValue = getValueOf(rhs, values);

        // 如果任一操作数为NAC，结果为NAC
        if (lhsValue.isNac() || rhsValue.isNac()) {
            return FlowValue.nac();
        }

        // 如果任一操作数为UNDEF，结果为UNDEF
        if (lhsValue.isUndef() || rhsValue.isUndef()) {
            return FlowValue.undef();
        }

        // 如果两个操作数都是常量，计算结果
        if (lhsValue.isConstant() && rhsValue.isConstant()) {
            int lhsConst = lhsValue.getConstValue();
            int rhsConst = rhsValue.getConstValue();

            switch (opcode) {
                case LLVMAdd:
                    return FlowValue.constant(lhsConst + rhsConst);
                case LLVMSub:
                    return FlowValue.constant(lhsConst - rhsConst);
                case LLVMMul:
                    return FlowValue.constant(lhsConst * rhsConst);
                case LLVMSDiv:
                    if (rhsConst == 0)
                        return FlowValue.nac(); // 除零错误
                    return FlowValue.constant(lhsConst / rhsConst);
                case LLVMSRem:
                    if (rhsConst == 0)
                        return FlowValue.nac(); // 除零错误
                    return FlowValue.constant(lhsConst % rhsConst);
                default:
                    return FlowValue.nac();
            }
        }

        return FlowValue.nac();
    }

    /**
     * 评估Load指令
     */
    private FlowValue evaluateLoad(LLVMValueRef inst, Map<LLVMValueRef, FlowValue> values,
            Map<String, Integer> globalInitValues) {
        LLVMValueRef ptr = LLVMGetOperand(inst, 0);
        String ptrName = LLVMGetValueName(ptr).getString();

        // 检查是否是全局变量
        boolean isGlobal = LLVMIsAGlobalVariable(ptr) != null;

        // 为全局变量添加@前缀，保持与store操作一致
        if (isGlobal)
            ptrName = "@" + ptrName;

        // 首先从memoryValues中查找内存位置的当前状态
        if (memoryValues.containsKey(ptrName))
        {
            return memoryValues.get(ptrName);
        }

        // 如果是全局变量且尚未在memoryValues中记录（可能尚未被store指令修改）
        if (isGlobal && globalInitValues.containsKey(ptrName))
        {
            // 使用初始值
            FlowValue initialValue = FlowValue.constant(globalInitValues.get(ptrName));
            // 存入memoryValues以便后续使用
            memoryValues.put(ptrName, initialValue);
            return initialValue;
        }

        // 移除旧的查找store指令的代码，现在我们完全通过memoryValues来追踪内存状态

        // 找不到对应的内存状态，返回NAC
        return FlowValue.nac();
    }

    private FlowValue evaluateStore(LLVMValueRef inst, Map<LLVMValueRef, FlowValue> values,
                                    Map<String, Integer> globalInitValues)
    {
        // 获取存储的值和目标地址
        LLVMValueRef valueToStore = LLVMGetOperand(inst, 0);
        LLVMValueRef ptr = LLVMGetOperand(inst, 1);
        String ptrName = LLVMGetValueName(ptr).getString();

        boolean isGlobal = LLVMIsAGlobalVariable(ptr) != null;

        if(isGlobal)
            ptrName = "@" + ptrName;

        // 获取存储值的状态
        FlowValue storedValue = getValueOf(valueToStore, values);

        FlowValue oldValue;

        // 对于全局变量，如果内存中没有记录，则获取初始值
        if (isGlobal && !memoryValues.containsKey(ptrName))
        {

            if (globalInitValues.containsKey(ptrName))
            {
                oldValue = FlowValue.constant(globalInitValues.get(ptrName));
            }
            else
            {
                oldValue = FlowValue.undef();
            }
        }
        else
        {
            // 获取当前内存位置的状态
            oldValue = memoryValues.getOrDefault(ptrName, FlowValue.undef());
        }

        FlowValue newValue = oldValue; // 默认不变

        // 根据规则更新内存位置的状态
        if (oldValue.isUndef())
        {
            // undef遇到任何值，采用新值
            newValue = storedValue;
        }
        else if (oldValue.isConstant())
        {
            // const遇到不同const或nac，变为nac
            if (storedValue.isConstant())
            {
                if (!oldValue.getConstValue().equals(storedValue.getConstValue()))
                {
                    newValue = FlowValue.nac();
                }
                // 相同常量值保持不变
            }
            else if (storedValue.isNac())
            {
                newValue = FlowValue.nac();
            }
            // storedValue是undef，保持原值不变
        }
        // 当前值是nac，保持不变

        // 更新内存位置的状态
        memoryValues.put(ptrName, newValue);

        // 返回更新后的内存位置状态
        return newValue;
    }

    /**
     * 评估比较指令
     */
    private FlowValue evaluateICmp(LLVMValueRef inst, Map<LLVMValueRef, FlowValue> values) {
        int predicate = LLVMGetICmpPredicate(inst);
        LLVMValueRef lhs = LLVMGetOperand(inst, 0);
        LLVMValueRef rhs = LLVMGetOperand(inst, 1);

        FlowValue lhsValue = getValueOf(lhs, values);
        FlowValue rhsValue = getValueOf(rhs, values);

        if (lhsValue.isNac() || rhsValue.isNac()) {
            return FlowValue.nac();
        }

        if (lhsValue.isUndef() || rhsValue.isUndef()) {
            return FlowValue.undef();
        }

        if (lhsValue.isConstant() && rhsValue.isConstant()) {
            int lhsConst = lhsValue.getConstValue();
            int rhsConst = rhsValue.getConstValue();
            boolean result;

            switch (predicate) {
                case LLVMIntEQ:
                    result = lhsConst == rhsConst;
                    break;
                case LLVMIntNE:
                    result = lhsConst != rhsConst;
                    break;
                case LLVMIntSGT:
                    result = lhsConst > rhsConst;
                    break;
                case LLVMIntSGE:
                    result = lhsConst >= rhsConst;
                    break;
                case LLVMIntSLT:
                    result = lhsConst < rhsConst;
                    break;
                case LLVMIntSLE:
                    result = lhsConst <= rhsConst;
                    break;
                default:
                    return FlowValue.nac();
            }

            return FlowValue.constant(result ? 1 : 0);
        }

        return FlowValue.nac();
    }

    /**
     * 评估扩展指令
     */
    private FlowValue evaluateExtension(LLVMValueRef inst, Map<LLVMValueRef, FlowValue> values) {
        LLVMValueRef operand = LLVMGetOperand(inst, 0);
        return getValueOf(operand, values);
    }

    /**
     * 获取操作数的值
     */
    private FlowValue getValueOf(LLVMValueRef operand, Map<LLVMValueRef, FlowValue> values) {
        if (LLVMIsAConstantInt(operand) != null) {
            long constValue = LLVMConstIntGetSExtValue(operand);
            return FlowValue.constant((int) constValue);
        }

        return values.getOrDefault(operand, FlowValue.undef());
    }

    /**
     * 应用优化 - 替换常量并删除冗余指令
     *
     * @param func   要优化的函数
     * @param values 常量传播分析结果
     * @return 是否进行了优化
     */
    private boolean applyOptimization(LLVMValueRef func, Map<LLVMValueRef, FlowValue> values) {
        boolean changed = false;
        List<LLVMValueRef> toRemove = new ArrayList<>();

        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                FlowValue value = values.get(inst);

                if (value != null && value.isConstant()) {
                    // 创建常量
                    LLVMValueRef constValue = LLVMConstInt(LLVMTypeOf(inst), value.getConstValue(), 0);

                    // 替换所有使用点
                    LLVMReplaceAllUsesWith(inst, constValue);

                    // 标记要删除的指令
                    toRemove.add(inst);
                    changed = true;
                }
            }
        }

        // 删除已被替换的指令
        for (LLVMValueRef inst : toRemove) {
            LLVMInstructionEraseFromParent(inst);
        }

        return changed;
    }
}
