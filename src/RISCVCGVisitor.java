import org.llvm4j.llvm4j.*;
import org.bytedeco.llvm.LLVM.*;
import org.llvm4j.llvm4j.Module;

import static org.bytedeco.llvm.global.LLVM.*;

public class RISCVCGVisitor {
    private final Module module;
    private final AsmBuilder asmBuilder;
    private final RegisterAllocator registerAllocator;
    private final MemoryRegisterAllocator memoryAllocator;
    private StringBuilder asmCode;
    private int stackOffset;
    private int currentPosition; // 添加当前位置跟踪

    public RISCVCGVisitor(Module module) {
        this.module = module;
        this.asmBuilder = new AsmBuilder();
        this.registerAllocator = new RegisterAllocator();
        this.memoryAllocator = new MemoryRegisterAllocator();
        this.asmCode = new StringBuilder();
        this.stackOffset = 0;
        this.currentPosition = 0; // 初始化当前位置
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
                }
            }
        }

        // 2. 处理函数
        for (LLVMValueRef func = LLVMGetFirstFunction(moduleRef); func != null; func = LLVMGetNextFunction(func)) {
            String funcName = LLVMGetValueName(func).getString();

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
                    currentPosition++; // 每处理一条指令，当前位置加1
                    int opcode = LLVMGetInstructionOpcode(inst);
                    int operandNum = LLVMGetNumOperands(inst);

                    switch (opcode) {
                        case LLVMAlloca:
                            // 分配栈空间
                            String varName = LLVMGetValueName(inst).getString();
                            int allocOffset = memoryAllocator.allocateVariable(varName);
                            break;

                        case LLVMStore:
                            if (operandNum == 2) {
                                LLVMValueRef value = LLVMGetOperand(inst, 0);
                                LLVMValueRef ptr = LLVMGetOperand(inst, 1);
                                String ptrName = LLVMGetValueName(ptr).getString();
                                int storeOffset = memoryAllocator.getVariableOffset(ptrName);

                                // 判断是否是常量
                                if (LLVMIsAConstantInt(value) != null) {
                                    // 如果是常量，使用 li 加载立即数
                                    long constValue = LLVMConstIntGetSExtValue(value);
                                    String tempReg = registerAllocator.allocateRegister("temp", currentPosition);
                                    asmCode.append(asmBuilder.emitLoadImmediate(tempReg, String.valueOf(constValue)));
                                    asmCode.append(asmBuilder.emitStore(tempReg, String.valueOf(storeOffset)));
                                    registerAllocator.freeRegister(tempReg);
                                } else {
                                    // 如果不是常量，获取值所在的寄存器
                                    String valueName = LLVMGetValueName(value).getString();
                                    String valueReg = registerAllocator.getRegisterForVariable(valueName);
                                    if (valueReg == null) {
                                        // 如果变量没有分配寄存器，分配一个并加载值
                                        valueReg = registerAllocator.allocateRegister(valueName, currentPosition);
                                        asmCode.append(asmBuilder.emitLoad(valueReg,
                                                String.valueOf(memoryAllocator.getVariableOffset(valueName))));
                                        registerAllocator.mapVariableToRegister(valueName, valueReg);
                                    }
                                    asmCode.append(asmBuilder.emitStore(valueReg, String.valueOf(storeOffset)));
                                    registerAllocator.freeRegister(valueReg);
                                    registerAllocator.mapVariableToRegister(valueName, null);
                                }
                            }
                            break;

                        case LLVMLoad:
                            if (operandNum == 1) {
                                LLVMValueRef ptr = LLVMGetOperand(inst, 0);
                                String ptrName = LLVMGetValueName(ptr).getString();
                                String resultName = LLVMGetValueName(inst).getString();
                                int loadOffset = memoryAllocator.getVariableOffset(ptrName);

                                // 为结果分配寄存器
                                String resultReg = registerAllocator.allocateRegister(resultName, currentPosition);
                                asmCode.append(asmBuilder.emitLoad(resultReg, String.valueOf(loadOffset)));
                                registerAllocator.mapVariableToRegister(resultName, resultReg);
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

                                // 获取或分配操作数1的寄存器
                                String op1Name = LLVMGetValueName(op1).getString();
                                String op1Reg = registerAllocator.getRegisterForVariable(op1Name);
                                boolean op1IsTemp = false;
                                if (op1Reg == null) {
                                    op1Reg = registerAllocator.allocateRegister(op1Name, currentPosition);
                                    op1IsTemp = true;
                                    if (LLVMIsAConstantInt(op1) != null) {
                                        long constValue = LLVMConstIntGetSExtValue(op1);
                                        asmCode.append(
                                                asmBuilder.emitLoadImmediate(op1Reg, String.valueOf(constValue)));
                                    } else {
                                        asmCode.append(asmBuilder.emitLoad(op1Reg,
                                                String.valueOf(memoryAllocator.getVariableOffset(op1Name))));
                                    }
                                    registerAllocator.mapVariableToRegister(op1Name, op1Reg);
                                }

                                // 获取或分配操作数2的寄存器
                                String op2Name = LLVMGetValueName(op2).getString();
                                String op2Reg = registerAllocator.getRegisterForVariable(op2Name);
                                boolean op2IsTemp = false;
                                if (op2Reg == null) {
                                    op2Reg = registerAllocator.allocateRegister(op2Name, currentPosition);
                                    op2IsTemp = true;
                                    if (LLVMIsAConstantInt(op2) != null) {
                                        long constValue = LLVMConstIntGetSExtValue(op2);
                                        asmCode.append(
                                                asmBuilder.emitLoadImmediate(op2Reg, String.valueOf(constValue)));
                                    } else {
                                        asmCode.append(asmBuilder.emitLoad(op2Reg,
                                                String.valueOf(memoryAllocator.getVariableOffset(op2Name))));
                                    }
                                    registerAllocator.mapVariableToRegister(op2Name, op2Reg);
                                }

                                // 为结果分配寄存器
                                String resultReg = registerAllocator.allocateRegister(resultName, currentPosition);
                                String op = getOperation(opcode);
                                asmCode.append(asmBuilder.emitBinaryOperation(op, resultReg, op1Reg, op2Reg));
                                registerAllocator.mapVariableToRegister(resultName, resultReg);

                                // 释放临时操作数寄存器
                                if (op1IsTemp) {
                                    registerAllocator.freeRegister(op1Reg);
                                    registerAllocator.mapVariableToRegister(op1Name, null);
                                }
                                if (op2IsTemp) {
                                    registerAllocator.freeRegister(op2Reg);
                                    registerAllocator.mapVariableToRegister(op2Name, null);
                                }
                            }
                            break;

                        case LLVMRet:
                            if (operandNum == 1) {
                                LLVMValueRef retValue = LLVMGetOperand(inst, 0);
                                if (LLVMIsAConstantInt(retValue) != null) {
                                    // 是常量，直接加载到 a0
                                    long constValue = LLVMConstIntGetSExtValue(retValue);
                                    asmCode.append(asmBuilder.emitLoadImmediate("a0", String.valueOf(constValue)));
                                } else {
                                    // 不是常量，需要获取变量值
                                    String retName = LLVMGetValueName(retValue).getString();
                                    String retReg = registerAllocator.getRegisterForVariable(retName);

                                    if (retReg == null) {
                                        // 如果变量没有分配寄存器，分配一个并加载值
                                        retReg = registerAllocator.allocateRegister(retName, currentPosition);
                                        asmCode.append(asmBuilder.emitLoad(retReg,
                                                String.valueOf(memoryAllocator.getVariableOffset(retName))));
                                    }

                                    // 将值移动到 a0 寄存器
                                    asmCode.append(asmBuilder.emitAssignment("a0", retReg));

                                    // 释放临时寄存器
                                    if (retReg != null && !retReg.equals("a0")) {
                                        registerAllocator.freeRegister(retReg);
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
                                asmCode.append(asmBuilder.emitReturn(null));
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

    private int calculateStackSize(LLVMValueRef func) {
        int size = 0;
        // 遍历所有基本块和指令，计算需要的栈空间
        for (LLVMBasicBlockRef bb = LLVMGetFirstBasicBlock(func); bb != null; bb = LLVMGetNextBasicBlock(bb)) {
            for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
                if (LLVMGetInstructionOpcode(inst) == LLVMAlloca) {
                    size += 4; // 每个变量分配4字节
                }
            }
        }
        // 按16字节对齐
        return (size + 15) & ~15;
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
                return "div"; // 有符号除法
            case LLVMUDiv:
                return "divu"; // 无符号除法
            case LLVMSRem:
                return "rem"; // 有符号取模
            case LLVMURem:
                return "remu"; // 无符号取模
            default:
                throw new IllegalArgumentException("Unknown operation: " + opcode);
        }
    }
}
