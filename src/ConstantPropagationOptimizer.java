import org.bytedeco.llvm.LLVM.*;
import org.bytedeco.llvm.global.LLVM;

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

        /**
         * 实现meet操作，类似于ValueLattice中的meet方法
         */
        public static FlowValue meet(FlowValue a, FlowValue b) {
            if (a.isUndef())
                return b;
            if (b.isUndef())
                return a;
            if (a.isNac() || b.isNac())
                return FlowValue.nac();

            // 都是常量，如果相等返回其中一个，否则返回NAC
            if (a.isConstant() && b.isConstant()) {
                if (a.getConstValue().equals(b.getConstValue())) {
                    return a;
                } else {
                    return FlowValue.nac();
                }
            }

            return FlowValue.nac();
        }
    }

    /**
     * 指令状态类，包含指令引用及其对应的in和out值
     */
    private static class InstructionState {
        private LLVMValueRef instruction; // 指令引用
        private Map<String, FlowValue> inValues; // 输入数据流值，变量名 -> 值
        private Map<String, FlowValue> outValues; // 输出数据流值，变量名 -> 值
        private String instructionString; // 指令的字符串表示

        public InstructionState(LLVMValueRef instruction) {
            this.instruction = instruction;
            this.inValues = new HashMap<>();
            this.outValues = new HashMap<>();
            this.instructionString = LLVMPrintValueToString(instruction).getString();
        }

        public LLVMValueRef getInstruction() {
            return instruction;
        }

        public String getInstructionString() {
            return instructionString;
        }

        public FlowValue getInValue(String var) {
            return inValues.getOrDefault(var, FlowValue.undef());
        }

        public void setInValue(String var, FlowValue value) {
            inValues.put(var, value);
        }

        public Map<String, FlowValue> getAllInValues() {
            return inValues;
        }

        public FlowValue getOutValue(String var) {
            return outValues.getOrDefault(var, FlowValue.undef());
        }

        public void setOutValue(String var, FlowValue value) {
            outValues.put(var, value);
        }

        public Map<String, FlowValue> getAllOutValues() {
            return outValues;
        }

        public void setAllOutValues(Map<String, FlowValue> values) {
            for (Map.Entry<String, FlowValue> entry : values.entrySet()) {
                outValues.put(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof InstructionState))
                return false;
            InstructionState other = (InstructionState) obj;

            return this.instruction.equals(other.instruction);
        }

        @Override
        public int hashCode() {
            return instruction.hashCode();
        }

        @Override
        public String toString() {
            return instructionString;
        }
    }

    private LLVMModuleRef module;
    private Set<String> globalVariables = new HashSet<>();
    private Map<String, FlowValue> memoryValues = new HashMap<>();

    // 控制流图：前驱和后继映射
    private Map<InstructionState, List<InstructionState>> preds = new HashMap<>();
    private Map<InstructionState, List<InstructionState>> succs = new HashMap<>();

    // 指令状态映射：LLVMValueRef -> InstructionState
    private Map<LLVMValueRef, InstructionState> instructionStates = new HashMap<>();

    // 添加一个有序的指令状态列表，保持原始的指令顺序
    private List<InstructionState> orderedInstructionStates = new ArrayList<>();

    // 局部"只写一次且写常量"的映射：alloca -> CONST(c)
    private Map<LLVMValueRef, FlowValue> singleStoreLocals = new HashMap<>();

    // 全局"只写一次且写常量"的映射：@g -> CONST(c)
    private Map<LLVMValueRef, FlowValue> singleStoreGlobals = new HashMap<>();

    // 指令字符串到值的映射
    private Map<String, LLVMValueRef> instructionStringToValue = new HashMap<>();
    private Map<LLVMValueRef, String> instructionValueToString = new HashMap<>();

    /**
     * 记录所有指令的字符串表示和引用之间的映射
     */
    private void recordAllInstructions() {
        instructionStringToValue.clear();
        instructionValueToString.clear();

        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
                    for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(
                            inst)) {
                        String instStr = LLVMPrintValueToString(inst).getString();
                        instructionStringToValue.put(instStr, inst);
                        instructionValueToString.put(inst, instStr);
                    }
                }
            }
        }
    }

    /**
     * 收集只写一次的局部变量
     */
    private void collectSingleStoreLocals() {
        // alloca -> [store指令] 映射
        Map<LLVMValueRef, List<LLVMValueRef>> storeMap = new HashMap<>();

        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null
                && LLVMIsDeclaration(func) == 0; func = LLVMGetNextFunction(func)) {

            for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {

                for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(
                        inst)) {

                    if (LLVMIsAStoreInst(inst) != null) {
                        LLVMValueRef val = LLVMGetOperand(inst, 0);
                        LLVMValueRef ptr = LLVMGetOperand(inst, 1);

                        if (LLVMIsAAllocaInst(ptr) != null) {
                            storeMap.computeIfAbsent(ptr, k -> new ArrayList<>()).add(inst);
                        }
                    }
                }
            }
        }

        // 遍历所有 alloca -> store 列表，筛选出只写一次且是常量
        for (Map.Entry<LLVMValueRef, List<LLVMValueRef>> entry : storeMap.entrySet()) {
            LLVMValueRef alloca = entry.getKey();
            List<LLVMValueRef> stores = entry.getValue();

            if (stores.size() == 1) {
                LLVMValueRef storeInst = stores.get(0);
                LLVMValueRef val = LLVMGetOperand(storeInst, 0);
                if (LLVMIsAConstantInt(val) != null) {
                    long c = LLVMConstIntGetSExtValue(val);
                    singleStoreLocals.put(alloca, FlowValue.constant((int) c));
                }
            }
        }
    }

    /**
     * 收集只写一次的全局变量
     */
    private void collectSingleStoreGlobals() {
        // map: global -> firstStoreConst, 第二次写就标为 NAC
        Map<LLVMValueRef, FlowValue> seen = new HashMap<>();

        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {

            for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {

                for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(
                        inst)) {

                    if (LLVMIsAStoreInst(inst) != null) {
                        LLVMValueRef value = LLVMGetOperand(inst, 0);
                        LLVMValueRef ptr = LLVMGetOperand(inst, 1);

                        if (LLVMIsAGlobalVariable(ptr) != null) {
                            FlowValue cur = seen.get(ptr);

                            if (cur == null && LLVMIsAConstantInt(value) != null) {
                                long constVal = LLVMConstIntGetSExtValue(value);
                                seen.put(ptr, FlowValue.constant((int) constVal));
                            } else {
                                // 出现第二次写（或写非常量）→ NAC
                                seen.put(ptr, FlowValue.nac());
                            }
                        }
                    }
                }
            }
        }

        // 过滤掉 NAC，只留下真正"写一次常量"的全局
        for (Map.Entry<LLVMValueRef, FlowValue> entry : seen.entrySet()) {
            if (entry.getValue().isConstant()) {
                singleStoreGlobals.put(entry.getKey(), entry.getValue());
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
        collectAllVariables();
        recordAllInstructions();
        collectSingleStoreGlobals();
        collectSingleStoreLocals();
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
     * 收集模块中的所有变量（全局变量和局部变量）
     */
    private void collectAllVariables() {
        // 添加全局变量
        globalVariables.addAll(globalVariables);

        // 收集局部变量（包括alloca创建的变量和函数参数）
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {

                // 收集函数内部的局部变量（通过alloca指令）
                for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
                    for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(
                            inst)) {
                        if (LLVMIsAAllocaInst(inst) != null) {
                            String varName = LLVMGetValueName(inst).getString();
                            if (!varName.isEmpty()) {
                                globalVariables.add(varName);
                            }
                        }
                        // 也可以收集其他定义的值（如计算结果等）
                        else if (LLVMIsAInstruction(inst) != null && LLVMGetInstructionOpcode(inst) != LLVMStore) {
                            String instName = LLVMGetValueName(inst).getString();
                            if (!instName.isEmpty()) {
                                globalVariables.add(instName);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 运行常量传播优化
     *
     * @return 是否进行了优化
     */
    public boolean run() {
        boolean changed = false;

        // 对每个函数进行常量传播优化
        for (LLVMValueRef func = LLVMGetFirstFunction(module); func != null; func = LLVMGetNextFunction(func)) {
            // 跳过外部函数声明
            if (LLVMIsAFunction(func) != null && LLVMCountBasicBlocks(func) > 0) {
                changed |= propagateConstants(func);
            }
        }

        return changed;
    }

    /**
     * 在单个函数中执行常量传播
     *
     * @param func 待优化的函数
     * @return 如果函数被修改，返回true
     */
    private boolean propagateConstants(LLVMValueRef func) {
        // 清除之前的状态
        memoryValues.clear();
        instructionStates.clear();
        preds.clear();
        succs.clear();

        // 构建控制流图和指令状态
        buildCFG(func);

        // 处理全局变量初始值
        Map<String, Integer> globalInitValues = collectGlobalInitValues();

        // 执行工作列表算法
        worklistSolve();

        // 根据分析结果优化代码
        return rewriteConstants();
    }

    /**
     * 构建控制流图并创建指令状态
     */
    private void buildCFG(LLVMValueRef func) {
        Map<LLVMBasicBlockRef, InstructionState> firstInstInBlock = new HashMap<>();

        // 清空指令状态
        instructionStates.clear();
        orderedInstructionStates.clear();

        // 第一遍：为每个指令创建状态
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {

            LLVMValueRef firstInst = null;

            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {

                // 创建指令状态
                InstructionState instState = new InstructionState(inst);
                instructionStates.put(inst, instState);

                // 按原始顺序添加到有序列表
                orderedInstructionStates.add(instState);

                if (firstInst == null) {
                    firstInst = inst;
                    firstInstInBlock.put(bb, instState);
                }
            }
        }

        // 第二遍：构建控制流图
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {

            InstructionState prevState = null;

            // 建立基本块内指令之间的顺序关系
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {

                InstructionState currentState = instructionStates.get(inst);

                if (prevState != null) {
                    addEdge(prevState, currentState);
                }

                prevState = currentState;
            }

            // 处理终结指令
            LLVMValueRef terminator = LLVMGetBasicBlockTerminator(bb);
            if (terminator != null) {
                InstructionState terminatorState = instructionStates.get(terminator);

                // 处理分支指令
                if (LLVMGetInstructionOpcode(terminator) == LLVMBr) {
                    int numSuccessors = LLVMGetNumSuccessors(terminator);

                    for (int i = 0; i < numSuccessors; i++) {
                        LLVMBasicBlockRef succBB = LLVMGetSuccessor(terminator, i);
                        InstructionState succFirstState = firstInstInBlock.get(succBB);

                        if (succFirstState != null) {
                            addEdge(terminatorState, succFirstState);
                        }
                    }
                }
            }
        }
    }

    /**
     * 添加控制流边
     */
    private void addEdge(InstructionState from, InstructionState to) {
        succs.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        preds.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
    }

    /**
     * 工作列表算法求解数据流方程
     */
    // todo 目前是in,out使用指令名称来作为key，不妥，换为变量名
    private void worklistSolve() {
        // 使用有序的指令列表初始化工作列表，保持原始执行顺序
        List<InstructionState> worklist = new LinkedList<>(orderedInstructionStates);

        while (!worklist.isEmpty()) {
            // 获取并移除第一个元素
            InstructionState state = worklist.remove(0);

            // 合并前驱的out值
            Map<String, FlowValue> mergedInValues = new HashMap<>();
            List<InstructionState> predecessors = preds.getOrDefault(state, Collections.emptyList());

            // 如果没有前驱，初始化一个空的in值映射
            // 但不要跳过处理，因为我们仍需要执行转移函数
            if (predecessors.isEmpty()) {
                // 保持空的mergedInValues，但继续处理
            } else {
                // 合并所有前驱的out值
                for (InstructionState pred : predecessors) {
                    Map<String, FlowValue> predOut = pred.getAllOutValues();

                    for (Map.Entry<String, FlowValue> entry : predOut.entrySet()) {
                        String var = entry.getKey();
                        FlowValue predValue = entry.getValue();

                        if (mergedInValues.containsKey(var)) {
                            FlowValue currentValue = mergedInValues.get(var);
                            mergedInValues.put(var, FlowValue.meet(currentValue, predValue));
                        } else {
                            mergedInValues.put(var, predValue);
                        }
                    }
                }
            }

            // 更新当前指令的in值
            boolean inChanged = false;
            Map<String, FlowValue> oldInValues = new HashMap<>(state.getAllInValues());

            for (Map.Entry<String, FlowValue> entry : mergedInValues.entrySet()) {
                String var = entry.getKey();
                FlowValue newVal = entry.getValue();
                FlowValue oldVal = state.getInValue(var);

                if (!oldVal.equals(newVal)) {
                    state.setInValue(var, newVal);
                    inChanged = true;
                }
            }

            // 转移函数
            Map<String, FlowValue> oldOutValues = new HashMap<>(state.getAllOutValues());
            Map<String, FlowValue> newOutValues = transfer(state.getInstruction(), mergedInValues);

            // 检查out值是否发生变化
            boolean changed = false;
            if (oldOutValues.size() != newOutValues.size()) {
                changed = true;
            } else {
                for (Map.Entry<String, FlowValue> entry : newOutValues.entrySet()) {
                    String var = entry.getKey();
                    FlowValue newVal = entry.getValue();
                    FlowValue oldVal = oldOutValues.getOrDefault(var, FlowValue.undef());

                    if (!oldVal.equals(newVal)) {
                        changed = true;
                        break;
                    }
                }
            }

            // 如果输出值发生变化，更新状态并将后继加入工作列表
            if (changed) {
                state.setAllOutValues(newOutValues);

                for (InstructionState succ : succs.getOrDefault(state, Collections.emptyList())) {
                    worklist.add(succ);
                }
            }
        }
    }

    /**
     * 收集全局变量的初始值
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
     */
    private Map<String, FlowValue> transfer(LLVMValueRef inst, Map<String, FlowValue> inValues) {
        int opcode = LLVMGetInstructionOpcode(inst);
        Map<String, FlowValue> result = new HashMap<>(inValues); // 复制输入状态作为基础
        String instName = instructionValueToString.getOrDefault(inst, LLVMPrintValueToString(inst).getString());
        String resultName = LLVMGetValueName(inst).getString();
        if (LLVMIsAGlobalVariable(inst) != null)
            resultName = "@" + resultName;

        switch (opcode) {
            /* ---------- 二元整数运算 ---------- */
            case LLVMAdd:
            case LLVMSub:
            case LLVMMul:
            case LLVMSDiv:
            case LLVMSRem: {
                LLVMValueRef op1 = LLVMGetOperand(inst, 0);
                LLVMValueRef op2 = LLVMGetOperand(inst, 1);
                FlowValue a = valueOf(op1, inValues);
                FlowValue b = valueOf(op2, inValues);
                FlowValue res = evaluateBinaryOp(opcode, a, b);
                result.put(resultName, res);
                return result;
            }

            /* ---------- 关系运算 ---------- */
            case LLVMICmp: {
                int pred = LLVMGetICmpPredicate(inst);
                LLVMValueRef op1 = LLVMGetOperand(inst, 0);
                LLVMValueRef op2 = LLVMGetOperand(inst, 1);
                FlowValue a = valueOf(op1, inValues);
                FlowValue b = valueOf(op2, inValues);
                FlowValue res = evaluateICmp(pred, a, b);
                result.put(resultName, res);
                return result;
            }

            /* ---------- load ---------- */
            case LLVMLoad: {
                LLVMValueRef ptr = LLVMGetOperand(inst, 0);
                FlowValue res;

                // 首先检查是否是单次存储的局部变量
                FlowValue v = singleStoreLocals.get(ptr);
                if (v != null) {
                    res = v;
                } else {
                    // 然后检查是否是单次存储的全局变量
                    v = singleStoreGlobals.get(ptr);
                    if (v != null) {
                        res = v;
                    } else {
                        // 最后使用原有的load评估逻辑
                        res = evaluateLoad(inst, inValues);
                    }
                }

                result.put(resultName, res);
                return result;
            }

            /* ---------- store ---------- */
            case LLVMStore: {
                LLVMValueRef val = LLVMGetOperand(inst, 0);
                LLVMValueRef ptr = LLVMGetOperand(inst, 1);
                String ptrName = LLVMGetValueName(ptr).getString();
                if (LLVMIsAGlobalVariable(ptr) != null) {
                    ptrName = "@" + ptrName;
                    if (singleStoreGlobals.containsKey(ptr)) {
                        result.put(ptrName, valueOf(val, inValues));
                        return result;
                    }
                }

                FlowValue valValue = valueOf(val, inValues);
                FlowValue ptrValue = valueOf(ptr, inValues);
                result.put(ptrName, FlowValue.meet(valValue, ptrValue));

                return result;
            }

            /* ---------- 扩展指令 ---------- */
            case LLVMZExt:
            case LLVMSExt: {
                LLVMValueRef operand = LLVMGetOperand(inst, 0);
                FlowValue opVal = valueOf(operand, inValues);
                result.put(resultName, opVal);
                return result;
            }

            case LLVMBr:
                return result;

            case LLVMAlloca:
                result.put(resultName, FlowValue.undef());
                return result;

            /* ---------- 其他指令 ---------- */
            default:
                result.put(resultName, FlowValue.nac());
                return result;
        }
    }

    /**
     * 获取操作数的值
     */
    private FlowValue valueOf(LLVMValueRef v, Map<String, FlowValue> inValues) {
        if (LLVMIsAConstantInt(v) != null) {
            long constVal = LLVMConstIntGetSExtValue(v);
            return FlowValue.constant((int) constVal);
        }

        String vName = LLVMGetValueName(v).getString();
        if (inValues.containsKey(vName)) {
            return inValues.get(vName);
        }

        InstructionState state = instructionStates.get(v);
        if (state != null) {
            return state.getOutValue(vName); // 使用指令的输出值
        }

        return FlowValue.nac(); // 无法确定
    }

    /**
     * 评估二元操作
     */
    private FlowValue evaluateBinaryOp(int opcode, FlowValue a, FlowValue b) {
        if (a.isConstant() && b.isConstant()) {
            int av = a.getConstValue();
            int bv = b.getConstValue();
            int res;

            switch (opcode) {
                case LLVMAdd:
                    res = av + bv;
                    break;
                case LLVMSub:
                    res = av - bv;
                    break;
                case LLVMMul:
                    res = av * bv;
                    break;
                case LLVMSDiv:
                    if (bv == 0)
                        return FlowValue.nac(); // 除零错误
                    res = av / bv;
                    break;
                case LLVMSRem:
                    if (bv == 0)
                        return FlowValue.nac(); // 除零错误
                    res = av % bv;
                    break;
                default:
                    return FlowValue.nac(); // 不支持的操作码
            }
            return FlowValue.constant(res);
        }

        if (a.isNac() || b.isNac())
            return FlowValue.nac();
        return FlowValue.undef();
    }

    /**
     * 评估比较指令
     */
    private FlowValue evaluateICmp(int pred, FlowValue a, FlowValue b) {
        if (a.isConstant() && b.isConstant()) {
            boolean r;
            int av = a.getConstValue();
            int bv = b.getConstValue();

            switch (pred) {
                case LLVMIntEQ:
                    r = av == bv;
                    break;
                case LLVMIntNE:
                    r = av != bv;
                    break;
                case LLVMIntSLT:
                    r = av < bv;
                    break;
                case LLVMIntSLE:
                    r = av <= bv;
                    break;
                case LLVMIntSGT:
                    r = av > bv;
                    break;
                case LLVMIntSGE:
                    r = av >= bv;
                    break;
                default:
                    return FlowValue.nac(); // 其他谓词暂不处理
            }
            return FlowValue.constant(r ? 1 : 0);
        }

        if (a.isNac() || b.isNac())
            return FlowValue.nac();
        return FlowValue.undef();
    }

    /**
     * 评估Load指令
     */
    private FlowValue evaluateLoad(LLVMValueRef inst, Map<String, FlowValue> inValues) {
        LLVMValueRef ptr = LLVMGetOperand(inst, 0);
        String ptrName = LLVMGetValueName(ptr).getString();
        boolean isGlobal = LLVMIsAGlobalVariable(ptr) != null;

        if (isGlobal) {
            ptrName = "@" + LLVMGetValueName(ptr).getString();
        }

        // 首先检查是否在当前in映射中
        if (inValues.containsKey(ptrName)) {
            return inValues.get(ptrName);
        }

        // 从memoryValues中查找
        if (memoryValues.containsKey(ptrName)) {
            return memoryValues.get(ptrName);
        }

        // 对于全局变量，我们可以使用初始值
        Map<String, Integer> globalInitValues = collectGlobalInitValues();
        if (isGlobal && globalInitValues.containsKey(ptrName)) {
            FlowValue initialValue = FlowValue.constant(globalInitValues.get(ptrName));
            memoryValues.put(ptrName, initialValue);
            return initialValue;
        }

        return FlowValue.nac();
    }

    /**
     * 应用优化 - 替换常量并删除冗余指令
     */
    // todo 删除逻辑有问题
    private boolean rewriteConstants() {
        boolean changed = false;
        List<LLVMValueRef> toRemove = new ArrayList<>();

        for (Map.Entry<LLVMValueRef, InstructionState> entry : instructionStates.entrySet()) {
            LLVMValueRef inst = entry.getKey();
            InstructionState state = entry.getValue();
            String instString = state.getInstructionString();

            String LvalueName = LLVMGetValueName(inst).getString();

            // 查找该指令自身的输出值
            FlowValue outVal = state.getOutValue(LvalueName);

            if (outVal != null && outVal.isConstant()) {
                LLVMValueRef c = LLVMConstInt(LLVMTypeOf(inst), outVal.getConstValue(), 0);
                LLVMReplaceAllUsesWith(inst, c);
                toRemove.add(inst);
                changed = true;
            }
        }

        // 删除已被替换的指令
        for (LLVMValueRef inst : toRemove) {
            LLVMInstructionEraseFromParent(inst);
        }

        return changed;
    }
}
