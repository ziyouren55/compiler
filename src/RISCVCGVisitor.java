import org.llvm4j.llvm4j.*;
import org.bytedeco.llvm.LLVM.*;
import org.llvm4j.llvm4j.Module;

import static org.bytedeco.llvm.global.LLVM.*;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public class RISCVCGVisitor {
    private final Module module;
    private final AsmBuilder asmBuilder;
    private RegisterAllocator registerAllocator;
    private final MemoryRegisterAllocator memoryAllocator;
    private StringBuilder asmCode;
    private int stackOffset;
    private int currentPosition;

    public RISCVCGVisitor(Module module) {
        this.module = module;
        this.asmBuilder = new AsmBuilder();
        this.registerAllocator = new RegisterAllocator();
        this.memoryAllocator = new MemoryRegisterAllocator();
        this.asmCode = new StringBuilder();
        this.stackOffset = 0;
        this.currentPosition = 0;
    }

    public String generateCode() {
        LLVMModuleRef moduleRef = module.getRef();

        // 1. 处理全局变量
        for (LLVMValueRef value = LLVMGetFirstGlobal(moduleRef); value != null; value = LLVMGetNextGlobal(value)) {
            if (!LLVMIsAGlobalVariable(value).isNull()) {
                String name = LLVMGetValueName(value).getString();
                LLVMValueRef initValue = LLVMGetInitializer(value);
                if (LLVMIsAConstantInt(initValue) != null) {
                    long constValue = LLVMConstIntGetSExtValue(initValue);
                    asmCode.append(asmBuilder.emitGlobalVariable(name, String.valueOf(constValue)));
                    memoryAllocator.addGlobalVariable(name);
                }
            }
        }

        // 2. 处理函数
        for (LLVMValueRef func = LLVMGetFirstFunction(moduleRef); func != null; func = LLVMGetNextFunction(func)) {
            String funcName = LLVMGetValueName(func).getString();

            // 重置寄存器分配器和内存分配器
            registerAllocator = new RegisterAllocator();
            memoryAllocator.reset();
            currentPosition = 0;

            // 先扫描所有局部变量
            scanLocalVariables(func);
            int localVarsSize = memoryAllocator.getCurrentOffset();

            // 收集活跃区间
            collectLiveIntervals(func);

            // 设置寄存器分配器的基准偏移量
            registerAllocator.setBaseOffset(localVarsSize);

            // 执行寄存器分配
            registerAllocator.allocate();

            // 生成函数头
            asmCode.append(asmBuilder.emitFunction(funcName, ""));

            // 计算栈帧大小并分配
            int stackSize = calculateStackSize(func);
            asmCode.append(asmBuilder.emitStackFrame(stackSize));

            // 处理函数的基本块
            for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
                String bbName = LLVMGetBasicBlockName(bb).getString();
                asmCode.append(bbName).append(":\n");

                // 处理基本块中的指令
                for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(
                        inst)) {
                    currentPosition++;
                    int opcode = LLVMGetInstructionOpcode(inst);
                    int operandNum = LLVMGetNumOperands(inst);

                    switch (opcode) {
                        case LLVMAlloca:
                            String varName = LLVMGetValueName(inst).getString();
                            memoryAllocator.allocateVariable(varName);
                            break;

                        case LLVMStore:
                            if (operandNum == 2) {
                                LLVMValueRef value = LLVMGetOperand(inst, 0);
                                LLVMValueRef ptr = LLVMGetOperand(inst, 1);
                                String ptrName = LLVMGetValueName(ptr).getString();
                                String valueName = LLVMGetValueName(value).getString();

                                // 首先检查存储目标是否为全局变量
                                boolean isGlobalVar = LLVMIsAGlobalVariable(ptr) != null;

                                if (LLVMIsAConstantInt(value) != null) {
                                    long constValue = LLVMConstIntGetSExtValue(value);
                                    // 加载常量到临时寄存器
                                    String tempReg = "t6";
                                    asmCode.append(asmBuilder.emitLoadImmediate(tempReg, String.valueOf(constValue)));

                                    if (isGlobalVar) {
                                        // 如果是全局变量，使用全局变量存储指令
                                        asmCode.append(asmBuilder.emitStoreGlobal(tempReg, ptrName));
                                    } else {
                                        // 局部变量处理
                                        String reg = registerAllocator.getRegister(ptrName);
                                        if (reg != null) {
                                            int offset = memoryAllocator.getVariableOffset(ptrName);
                                            asmCode.append(asmBuilder.emitStore(tempReg, String.valueOf(offset)));
                                        } else {
                                            String spillLoc = registerAllocator.getSpillLocation(valueName);
                                            if (spillLoc != null) {
                                                asmCode.append(asmBuilder.emitStore(tempReg, spillLoc));
                                            }
                                        }
                                    }
                                } else {
                                    // 处理非常量值的存储
                                    String reg = registerAllocator.getRegister(valueName);
                                    if (reg != null) {
                                        if (isGlobalVar) {
                                            asmCode.append(asmBuilder.emitStoreGlobal(reg, ptrName));
                                        } else {
                                            int storeOffset = memoryAllocator.getVariableOffset(ptrName);
                                            asmCode.append(asmBuilder.emitStore(reg, String.valueOf(storeOffset)));
                                        }
                                    } else {
                                        String spillLoc = registerAllocator.getSpillLocation(valueName);
                                        if (spillLoc != null) {
                                            String tempReg = "t6";
                                            asmCode.append(asmBuilder.emitLoad(tempReg, spillLoc));
                                            if (isGlobalVar) {
                                                asmCode.append(asmBuilder.emitStoreGlobal(tempReg, ptrName));
                                            } else {
                                                int storeOffset = memoryAllocator.getVariableOffset(ptrName);
                                                asmCode.append(
                                                        asmBuilder.emitStore(tempReg, String.valueOf(storeOffset)));
                                            }
                                        }
                                    }
                                }
                            }
                            break;

                        case LLVMLoad:
                            if (operandNum == 1) {
                                LLVMValueRef ptr = LLVMGetOperand(inst, 0);
                                String ptrName = LLVMGetValueName(ptr).getString();
                                String resultName = LLVMGetValueName(inst).getString();
                                String reg = registerAllocator.getRegister(resultName);

                                if (reg != null) {
                                    if (LLVMIsAGlobalVariable(ptr) != null) {
                                        asmCode.append(asmBuilder.emitLoadGlobal(reg, ptrName));
                                    } else {
                                        int loadOffset = memoryAllocator.getVariableOffset(ptrName);
                                        asmCode.append(asmBuilder.emitLoad(reg, String.valueOf(loadOffset)));
                                    }
                                } else {
                                    String spillLoc = registerAllocator.getSpillLocation(resultName);
                                    if (spillLoc != null) {
                                        String tempReg = "t6";
                                        if (LLVMIsAGlobalVariable(ptr) != null) {
                                            asmCode.append(asmBuilder.emitLoadGlobal(tempReg, ptrName));
                                        } else {
                                            int loadOffset = memoryAllocator.getVariableOffset(ptrName);
                                            asmCode.append(asmBuilder.emitLoad(tempReg, String.valueOf(loadOffset)));
                                        }
                                        asmCode.append(asmBuilder.emitStore(tempReg, spillLoc));
                                    }
                                }
                            }
                            break;

                        case LLVMAdd:
                        case LLVMSub:
                        case LLVMMul:
                        case LLVMSDiv:
                        case LLVMUDiv:
                        case LLVMSRem:
                        case LLVMURem:
                            if (operandNum == 2) {
                                LLVMValueRef op1 = LLVMGetOperand(inst, 0);
                                LLVMValueRef op2 = LLVMGetOperand(inst, 1);
                                String resultName = LLVMGetValueName(inst).getString();
                                String resultReg = registerAllocator.getRegister(resultName);
                                String op1Name = LLVMGetValueName(op1).getString();
                                String op2Name = LLVMGetValueName(op2).getString();
                                String op1Reg = registerAllocator.getRegister(op1Name);
                                String op2Reg = registerAllocator.getRegister(op2Name);

                                if (resultReg != null) {
                                    if (LLVMIsAConstantInt(op1) != null) {
                                        long constValue = LLVMConstIntGetSExtValue(op1);
                                        asmCode.append(
                                                asmBuilder.emitLoadImmediate(resultReg, String.valueOf(constValue)));
                                    } else if (op1Reg != null) {
                                        asmCode.append(asmBuilder.emitAssignment(resultReg, op1Reg));
                                    } else {
                                        String spillLoc = registerAllocator.getSpillLocation(op1Name);
                                        if (spillLoc != null) {
                                            asmCode.append(asmBuilder.emitLoad(resultReg, spillLoc));
                                        }
                                    }

                                    if (LLVMIsAConstantInt(op2) != null) {
                                        long constValue = LLVMConstIntGetSExtValue(op2);
                                        String tempReg = "t6";
                                        asmCode.append(
                                                asmBuilder.emitLoadImmediate(tempReg, String.valueOf(constValue)));
                                        asmCode.append(asmBuilder.emitBinaryOperation(getOperation(opcode), resultReg,
                                                resultReg, tempReg));
                                    } else if (op2Reg != null) {
                                        asmCode.append(asmBuilder.emitBinaryOperation(getOperation(opcode), resultReg,
                                                resultReg, op2Reg));
                                    } else {
                                        String spillLoc = registerAllocator.getSpillLocation(op2Name);
                                        if (spillLoc != null) {
                                            String tempReg = "t6";
                                            asmCode.append(asmBuilder.emitLoad(tempReg, spillLoc));
                                            asmCode.append(asmBuilder.emitBinaryOperation(getOperation(opcode),
                                                    resultReg, resultReg, tempReg));
                                        }
                                    }
                                } else {
                                    String spillLoc = registerAllocator.getSpillLocation(resultName);
                                    if (spillLoc != null) {
                                        String tempReg = "t6";
                                        if (LLVMIsAConstantInt(op1) != null) {
                                            long constValue = LLVMConstIntGetSExtValue(op1);
                                            asmCode.append(
                                                    asmBuilder.emitLoadImmediate(tempReg, String.valueOf(constValue)));
                                        } else if (op1Reg != null) {
                                            asmCode.append(asmBuilder.emitAssignment(tempReg, op1Reg));
                                        } else {
                                            String op1SpillLoc = registerAllocator.getSpillLocation(op1Name);
                                            if (op1SpillLoc != null) {
                                                asmCode.append(asmBuilder.emitLoad(tempReg, op1SpillLoc));
                                            }
                                        }

                                        if (LLVMIsAConstantInt(op2) != null)
                                        {
                                            long constValue = LLVMConstIntGetSExtValue(op2);
                                            String tempReg2 = "t5";
                                            asmCode.append(
                                                asmBuilder.emitLoadImmediate(tempReg2, String.valueOf(constValue)));
                                            asmCode.append(asmBuilder.emitBinaryOperation(getOperation(opcode), tempReg,
                                                tempReg, tempReg2));
                                        }
                                        else if (op2Reg != null)
                                        {
                                            asmCode.append(asmBuilder.emitBinaryOperation(getOperation(opcode), tempReg,
                                                tempReg, op2Reg));
                                        } else {
                                            String op2SpillLoc = registerAllocator.getSpillLocation(op2Name);
                                            if (op2SpillLoc != null) {
                                                String tempReg2 = "t5";
                                                asmCode.append(asmBuilder.emitLoad(tempReg2, op2SpillLoc));
                                                asmCode.append(asmBuilder.emitBinaryOperation(getOperation(opcode),
                                                        tempReg, tempReg, tempReg2));
                                            }
                                        }
                                        asmCode.append(asmBuilder.emitStore(tempReg, spillLoc));
                                    }
                                }
                            }
                            break;

                        case LLVMRet:
                            if (operandNum == 1) {
                                LLVMValueRef retValue = LLVMGetOperand(inst, 0);
                                if (LLVMIsAConstantInt(retValue) != null) {
                                    long constValue = LLVMConstIntGetSExtValue(retValue);
                                    asmCode.append(asmBuilder.emitLoadImmediate("a0", String.valueOf(constValue)));
                                } else {
                                    String retName = LLVMGetValueName(retValue).getString();
                                    String retReg = registerAllocator.getRegister(retName);
                                    if (retReg != null) {
                                        asmCode.append(asmBuilder.emitAssignment("a0", retReg));
                                    } else {
                                        String spillLoc = registerAllocator.getSpillLocation(retName);
                                        if (spillLoc != null) {
                                            asmCode.append(asmBuilder.emitLoad("a0", spillLoc));
                                        }
                                    }
                                }
                            }

                            // 恢复栈帧
                            asmCode.append(asmBuilder.emitStackFrameRestore(stackSize));

                            // 根据函数类型选择返回方式
                            if (funcName.equals("main")) {
                                asmCode.append(asmBuilder.emitLoadImmediate("a7", "93"));
                                asmCode.append(asmBuilder.emitSystemCall());
                            } else {
                                // 修复非main函数的返回，使用ret指令
                                // 返回值已经在前面放入a0寄存器中
                                asmCode.append(asmBuilder.emitReturn(null));
                            }
                            break;

                        case LLVMCall:
                            // 处理函数调用指令
                            String callResultName = LLVMGetValueName(inst).getString();

                            // 获取被调用的函数
                            LLVMValueRef calledFunc = null;
                            try {
                                // 尝试获取被调用的函数
                                for (int i = 0; i < operandNum; i++) {
                                    LLVMValueRef op = LLVMGetOperand(inst, i);
                                    if (LLVMIsAFunction(op) != null) {
                                        calledFunc = op;
                                        break;
                                    }
                                }
                            } catch (Exception e) {
                                System.err.println("警告：无法获取被调用的函数：" + e.getMessage());
                            }

                            // 获取函数名
                            String calledFuncName = "<未知函数>";
                            if (calledFunc != null) {
                                calledFuncName = LLVMGetValueName(calledFunc).getString();
                            }

                            // 收集函数参数
                            List<String> args = new ArrayList<>();
                            int argIndex = 0;

                            // 跳过第一个操作数（被调用的函数）
                            for (int i = 1; i < operandNum; i++) {
                                LLVMValueRef arg = LLVMGetOperand(inst, i);

                                // 根据参数类型处理
                                if (LLVMIsAConstantInt(arg) != null) {
                                    // 常量参数
                                    long constValue = LLVMConstIntGetSExtValue(arg);
                                    String tempReg = "t" + (argIndex % 6); // 使用临时寄存器t0-t5
                                    asmCode.append(asmBuilder.emitLoadImmediate(tempReg, String.valueOf(constValue)));
                                    args.add(tempReg);
                                } else {
                                    // 变量参数
                                    String argName = LLVMGetValueName(arg).getString();
                                    String argReg = registerAllocator.getRegister(argName);

                                    if (argReg != null) {
                                        args.add(argReg);
                                    } else {
                                        // 处理溢出到栈的情况
                                        String spillLoc = registerAllocator.getSpillLocation(argName);
                                        if (spillLoc != null) {
                                            String tempReg = "t" + (argIndex % 6);
                                            asmCode.append(asmBuilder.emitLoad(tempReg, spillLoc));
                                            args.add(tempReg);
                                        }
                                    }
                                }

                                argIndex++;
                            }

                            // 生成函数调用
                            asmCode.append(asmBuilder.emitFunctionCall(calledFuncName, args));

                            // 处理返回值
                            if (callResultName != null && !callResultName.isEmpty()) {
                                String resultReg = registerAllocator.getRegister(callResultName);
                                if (resultReg != null) {
                                    // 将a0(返回值寄存器)移动到目标寄存器
                                    asmCode.append(asmBuilder.emitAssignment(resultReg, "a0"));
                                } else {
                                    // 处理溢出到栈的情况
                                    String spillLoc = registerAllocator.getSpillLocation(callResultName);
                                    if (spillLoc != null) {
                                        asmCode.append(asmBuilder.emitStore("a0", spillLoc));
                                    }
                                }
                            }
                            break;

                        case LLVMICmp:
                            if (operandNum == 2) {
                                // 获取比较操作数和谓词
                                LLVMValueRef op1 = LLVMGetOperand(inst, 0);
                                LLVMValueRef op2 = LLVMGetOperand(inst, 1);
                                int predicate = LLVMGetICmpPredicate(inst);

                                String resultName = LLVMGetValueName(inst).getString();
                                String resultReg = registerAllocator.getRegister(resultName);

                                // 加载第一个操作数
                                String op1Reg = loadOperandToRegister(op1, "t6");

                                // 加载第二个操作数
                                String op2Reg = loadOperandToRegister(op2, "t5");

                                if (resultReg != null) {
                                    // 根据谓词生成相应的比较指令
                                    switch (predicate) {
                                        case LLVMIntEQ: // ==
                                            asmCode.append(asmBuilder.emitEqual(resultReg, op1Reg, op2Reg));
                                            break;
                                        case LLVMIntNE: // !=
                                            asmCode.append(asmBuilder.emitNotEqual(resultReg, op1Reg, op2Reg));
                                            break;
                                        case LLVMIntSGT: // >
                                            asmCode.append(asmBuilder.emitGreaterThan(resultReg, op1Reg, op2Reg));
                                            break;
                                        case LLVMIntSGE: // >=
                                            asmCode.append(asmBuilder.emitGreaterEqual(resultReg, op1Reg, op2Reg));
                                            break;
                                        case LLVMIntSLT: // <
                                            asmCode.append(asmBuilder.emitLessThan(resultReg, op1Reg, op2Reg));
                                            break;
                                        case LLVMIntSLE: // <=
                                            asmCode.append(asmBuilder.emitLessEqual(resultReg, op1Reg, op2Reg));
                                            break;
                                        default:
                                            System.err.println("不支持的谓词: " + predicate);
                                    }
                                } else {
                                    // 处理溢出到栈的情况
                                    String spillLoc = registerAllocator.getSpillLocation(resultName);
                                    if (spillLoc != null) {
                                        String tempReg = "t4";
                                        // 根据谓词生成比较指令
                                        switch (predicate) {
                                            case LLVMIntEQ: // ==
                                                asmCode.append(asmBuilder.emitEqual(tempReg, op1Reg, op2Reg));
                                                break;
                                            case LLVMIntNE: // !=
                                                asmCode.append(asmBuilder.emitNotEqual(tempReg, op1Reg, op2Reg));
                                                break;
                                            case LLVMIntSGT: // >
                                                asmCode.append(asmBuilder.emitGreaterThan(tempReg, op1Reg, op2Reg));
                                                break;
                                            case LLVMIntSGE: // >=
                                                asmCode.append(asmBuilder.emitGreaterEqual(tempReg, op1Reg, op2Reg));
                                                break;
                                            case LLVMIntSLT: // <
                                                asmCode.append(asmBuilder.emitLessThan(tempReg, op1Reg, op2Reg));
                                                break;
                                            case LLVMIntSLE: // <=
                                                asmCode.append(asmBuilder.emitLessEqual(tempReg, op1Reg, op2Reg));
                                                break;
                                            default:
                                                System.err.println("不支持的谓词: " + predicate);
                                        }
                                        // 将结果存入栈
                                        asmCode.append(asmBuilder.emitStore(tempReg, spillLoc));
                                    }
                                }
                            }
                            break;

                        case LLVMBr:
                            if (operandNum == 3) {
                                // 条件分支：br i1 cond, label true_bb, label false_bb
                                LLVMValueRef condValue = LLVMGetOperand(inst, 0);
                                LLVMBasicBlockRef trueBlock = LLVMValueAsBasicBlock(LLVMGetOperand(inst, 2));
                                LLVMBasicBlockRef falseBlock = LLVMValueAsBasicBlock(LLVMGetOperand(inst, 1));

                                String trueBBName = LLVMGetBasicBlockName(trueBlock).getString();
                                String falseBBName = LLVMGetBasicBlockName(falseBlock).getString();

                                // 加载条件值到寄存器
                                String condReg = loadOperandToRegister(condValue, "t6");

                                // 生成条件跳转指令
                                asmCode.append(asmBuilder.emitBranchNotZero(condReg, trueBBName));
                                asmCode.append(asmBuilder.emitJump(falseBBName));
                            } else if (operandNum == 1) {
                                // 无条件跳转：br label dest_bb
                                LLVMBasicBlockRef destBlock = LLVMValueAsBasicBlock(LLVMGetOperand(inst, 0));
                                String destBBName = LLVMGetBasicBlockName(destBlock).getString();

                                // 生成无条件跳转指令
                                asmCode.append(asmBuilder.emitJump(destBBName));
                            }
                            break;

                        case LLVMZExt:
                            if (operandNum == 1) {
                                LLVMValueRef srcValue = LLVMGetOperand(inst, 0);
                                String resultName = LLVMGetValueName(inst).getString();
                                String resultReg = registerAllocator.getRegister(resultName);

                                // 加载源操作数
                                String srcReg = loadOperandToRegister(srcValue, "t6");

                                if (resultReg != null) {
                                    // 在RISC-V中，i1到i32的扩展可能不需要特殊指令
                                    asmCode.append(asmBuilder.emitAssignment(resultReg, srcReg));
                                } else {
                                    // 处理溢出情况
                                    String spillLoc = registerAllocator.getSpillLocation(resultName);
                                    if (spillLoc != null) {
                                        String tempReg = "t5";
                                        asmCode.append(asmBuilder.emitAssignment(tempReg, srcReg));
                                        asmCode.append(asmBuilder.emitStore(tempReg, spillLoc));
                                    }
                                }
                            }
                            break;

                        default:
                            break;
                    }
                }
            }
        }

        return asmCode.toString();
    }

    private void collectLiveIntervals(LLVMValueRef func) {
        // 用于存储每个变量的定义点和最后使用点
        Map<String, Integer> varDefPoints = new HashMap<>();
        Map<String, Integer> varLastUsePoints = new HashMap<>();

        // 第一次遍历：收集所有变量的定义点和最后使用点
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                int opcode = LLVMGetInstructionOpcode(inst);
                int operandNum = LLVMGetNumOperands(inst);

                // 收集使用点
                for (int i = 0; i < operandNum; i++) {
                    LLVMValueRef operand = LLVMGetOperand(inst, i);
                    if (LLVMIsAConstantInt(operand) == null) {
                        String varName = LLVMGetValueName(operand).getString();
                        if (!varName.isEmpty()) {
                            // 更新最后使用点
                            varLastUsePoints.put(varName, currentPosition);
                            // 如果是第一次使用，记录为定义点
                            if (!varDefPoints.containsKey(varName)) {
                                varDefPoints.put(varName, currentPosition);
                            }
                        }
                    }
                }

                // 收集定义点
                if (opcode != LLVMStore && opcode != LLVMAlloca) {
                    String varName = LLVMGetValueName(inst).getString();
                    if (!varName.isEmpty()) {
                        // 更新定义点
                        varDefPoints.put(varName, currentPosition);
                        // 如果是第一次定义，初始化最后使用点
                        if (!varLastUsePoints.containsKey(varName)) {
                            varLastUsePoints.put(varName, currentPosition);
                        }
                    }
                }

                currentPosition++;
            }
        }

        // 第二次遍历：构建活跃区间
        for (String varName : varDefPoints.keySet()) {
            int defPoint = varDefPoints.get(varName);
            int lastUsePoint = varLastUsePoints.get(varName);
            // 添加活跃区间，从定义点到最后使用点
            registerAllocator.addInterval(varName, defPoint, lastUsePoint + 1);
        }

        currentPosition = 0;
    }

    private int calculateStackSize(LLVMValueRef func) {
        int size = 0;
        // 遍历所有基本块和指令，计算需要的栈空间
        size += memoryAllocator.getCurrentOffset();
        // 加上寄存器溢出需要的空间
        size += registerAllocator.getStackSize();
        // 按16字节对齐
        return (size + 15) & ~15;
    }

    private void scanLocalVariables(LLVMValueRef func) {
    for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
        for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
            if (LLVMGetInstructionOpcode(inst) == LLVMAlloca) {
                String varName = LLVMGetValueName(inst).getString();
                memoryAllocator.allocateVariable(varName);
            }
        }
    }
}

    private String getOperation(int opcode) {
        switch (opcode) {
            case LLVMAdd:
                return "add";
            case LLVMSub:
                return "sub";
            case LLVMMul:
                return "mul";
            case LLVMSDiv:
                return "div";
            case LLVMUDiv:
                return "divu";
            case LLVMSRem:
                return "rem";
            case LLVMURem:
                return "remu";
            default:
                throw new IllegalArgumentException("Unknown operation: " + opcode);
        }
    }

    /**
     * 加载操作数到指定寄存器
     */
    private String loadOperandToRegister(LLVMValueRef operand, String defaultReg) {
        if (LLVMIsAConstantInt(operand) != null) {
            // 常量值直接加载到默认寄存器
            long constValue = LLVMConstIntGetSExtValue(operand);
            asmCode.append(asmBuilder.emitLoadImmediate(defaultReg, String.valueOf(constValue)));
            return defaultReg;
        } else {
            // 变量值
            String opName = LLVMGetValueName(operand).getString();
            String opReg = registerAllocator.getRegister(opName);

            if (opReg != null) {
                // 已分配寄存器，直接返回
                return opReg;
            } else {
                // 溢出到栈的情况
                String spillLoc = registerAllocator.getSpillLocation(opName);
                if (spillLoc != null) {
                    asmCode.append(asmBuilder.emitLoad(defaultReg, spillLoc));
                    return defaultReg;
                } else {
                    System.err.println("警告: 无法找到操作数 " + opName + " 的位置");
                    return defaultReg;
                }
            }
        }
    }
}
