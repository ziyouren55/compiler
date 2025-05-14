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

    public RISCVCGVisitor(Module module) {
        this.module = module;
        this.asmBuilder = new AsmBuilder();
        this.registerAllocator = new RegisterAllocator();
        this.memoryAllocator = new MemoryRegisterAllocator();
        this.asmCode = new StringBuilder();
        this.stackOffset = 0;
    }

    public String generateCode() {
        LLVMModuleRef moduleRef = module.getRef();

        // 1. 处理全局变量
        for (LLVMValueRef value = LLVMGetFirstGlobal(moduleRef); value != null; value = LLVMGetNextGlobal(value)) {
            if (!LLVMIsAGlobalVariable(value).isNull()) {
                String name = LLVMGetValueName(value).getString();
                LLVMValueRef initValue = LLVMGetInitializer(value);
                if (!LLVMIsAConstantInt(initValue).isNull()) {
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
                for (LLVMValueRef inst = LLVMGetFirstInstruction(bb); inst != null; inst = LLVMGetNextInstruction(inst)) {
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
                                String valueName = LLVMGetValueName(value).getString();
                                String ptrName = LLVMGetValueName(ptr).getString();
                                int storeOffset = memoryAllocator.getVariableOffset(ptrName);
                                asmCode.append(asmBuilder.emitStore(ptrName, String.valueOf(storeOffset)));
                            }
                            break;

                        case LLVMLoad:
                            if (operandNum == 1) {
                                LLVMValueRef ptr = LLVMGetOperand(inst, 0);
                                String ptrName = LLVMGetValueName(ptr).getString();
                                String resultName = LLVMGetValueName(inst).getString();
                                int loadOffset = memoryAllocator.getVariableOffset(ptrName);
                                asmCode.append(asmBuilder.emitLoad(resultName, String.valueOf(loadOffset)));
                            }
                            break;

                        case LLVMAdd:
                        case LLVMSub:
                        case LLVMMul:
                            if (operandNum == 2) {
                                LLVMValueRef op1 = LLVMGetOperand(inst, 0);
                                LLVMValueRef op2 = LLVMGetOperand(inst, 1);
                                String op1Name = LLVMGetValueName(op1).getString();
                                String op2Name = LLVMGetValueName(op2).getString();
                                String resultName = LLVMGetValueName(inst).getString();
                                String op = getOperation(opcode);
                                asmCode.append(asmBuilder.emitBinaryOperation(op, resultName, op1Name, op2Name));
                            }
                            break;

                        case LLVMRet:
                            if (operandNum == 1) {
                                LLVMValueRef retValue = LLVMGetOperand(inst, 0);
                                if (LLVMIsAConstantInt(retValue) == null) {
                                    String retName = LLVMGetValueName(retValue).getString();
                                    asmCode.append(asmBuilder.emitAssignment("a0", retName));
                                } else {
                                    long constValue = LLVMConstIntGetSExtValue(retValue);
                                    asmCode.append(asmBuilder.emitLoadImmediate("a0", String.valueOf(constValue)));
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
            case LLVMAdd: return "add";
            case LLVMSub: return "sub";
            case LLVMMul: return "mul";
            default: throw new IllegalArgumentException("Unknown operation: " + opcode);
        }
    }
}
