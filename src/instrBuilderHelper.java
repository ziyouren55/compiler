import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMBuilderRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.util.HashMap;
import java.util.Map;

import static org.bytedeco.llvm.global.LLVM.LLVMAdd;
import static org.bytedeco.llvm.global.LLVM.LLVMAlloca;
import static org.bytedeco.llvm.global.LLVM.LLVMBr;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildAdd;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildAlloca;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildBr;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildCondBr;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildICmp;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildLoad;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildMul;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildRet;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildRetVoid;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildSDiv;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildSRem;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildStore;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildSub;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildUDiv;
import static org.bytedeco.llvm.global.LLVM.LLVMBuildURem;
import static org.bytedeco.llvm.global.LLVM.LLVMGetAllocatedType;
import static org.bytedeco.llvm.global.LLVM.LLVMGetICmpPredicate;
import static org.bytedeco.llvm.global.LLVM.LLVMGetInstructionOpcode;
import static org.bytedeco.llvm.global.LLVM.LLVMGetNumOperands;
import static org.bytedeco.llvm.global.LLVM.LLVMGetOperand;
import static org.bytedeco.llvm.global.LLVM.LLVMGetValueName;
import static org.bytedeco.llvm.global.LLVM.LLVMICmp;
import static org.bytedeco.llvm.global.LLVM.LLVMLoad;
import static org.bytedeco.llvm.global.LLVM.LLVMMul;
import static org.bytedeco.llvm.global.LLVM.LLVMRet;
import static org.bytedeco.llvm.global.LLVM.LLVMSDiv;
import static org.bytedeco.llvm.global.LLVM.LLVMSRem;
import static org.bytedeco.llvm.global.LLVM.LLVMStore;
import static org.bytedeco.llvm.global.LLVM.LLVMSub;
import static org.bytedeco.llvm.global.LLVM.LLVMUDiv;
import static org.bytedeco.llvm.global.LLVM.LLVMURem;
import static org.bytedeco.llvm.global.LLVM.LLVMValueAsBasicBlock;

class InstrBuildHelper {
    /**
     * Builds a new instruction based on an existing instruction, applying operand replacements.
     *
     * @param builder       The LLVM builder to insert the new instruction.
     * @param existingInst  The instruction to replicate.
     * @return The new instruction (LLVMValueRef), or null if unsupported.
     * @throws UnsupportedOperationException if the instruction type is not supported.
     */
    public LLVMValueRef buildInstruction(
            LLVMBuilderRef builder,
            LLVMValueRef existingInst) {
        Map<LLVMValueRef, LLVMValueRef> operandMapping = new HashMap<>();

        int opcode = LLVMGetInstructionOpcode(existingInst);
        switch (opcode) {
            case LLVMAlloca: {
                // %ptr = alloca i32, align 4
                LLVMTypeRef type = LLVMGetAllocatedType(existingInst);
                return LLVMBuildAlloca(builder, type, LLVMGetValueName(existingInst).getString());
            }
            case LLVMStore: {
                // store i32 %val, i32* %ptr, align 4
                LLVMValueRef value = getMappedOperand(existingInst, 0, operandMapping);
                LLVMValueRef ptr = getMappedOperand(existingInst, 1, operandMapping);
                return LLVMBuildStore(builder, value, ptr);
            }
            case LLVMLoad: {
                // %val = load i32, i32* %ptr, align 4
                LLVMValueRef ptr = getMappedOperand(existingInst, 0, operandMapping);
                return LLVMBuildLoad(builder, ptr, LLVMGetValueName(existingInst).getString());
            }
            case LLVMAdd:
            case LLVMSub:
            case LLVMMul:
            case LLVMUDiv:
            case LLVMSDiv:
            case LLVMURem:
            case LLVMSRem: {
                // %res = add i32 %a, %b
                LLVMValueRef op1 = getMappedOperand(existingInst, 0, operandMapping);
                LLVMValueRef op2 = getMappedOperand(existingInst, 1, operandMapping);
                String name = LLVMGetValueName(existingInst).getString();
                switch (opcode) {
                    case LLVMAdd:
                        return LLVMBuildAdd(builder, op1, op2, name);
                    case LLVMSub:
                        return LLVMBuildSub(builder, op1, op2, name);
                    case LLVMMul:
                        return LLVMBuildMul(builder, op1, op2, name);
                    case LLVMUDiv:
                        return LLVMBuildUDiv(builder, op1, op2, name);
                    case LLVMSDiv:
                        return LLVMBuildSDiv(builder, op1, op2, name);
                    case LLVMURem:
                        return LLVMBuildURem(builder, op1, op2, name);
                    case LLVMSRem:
                        return LLVMBuildSRem(builder, op1, op2, name);
                    default:
                        throw new IllegalStateException("Unreachable");
                }
            }
            case LLVMICmp: {
                // %cmp = icmp eq i32 %a, %b
                LLVMValueRef op1 = getMappedOperand(existingInst, 0, operandMapping);
                LLVMValueRef op2 = getMappedOperand(existingInst, 1, operandMapping);
                int predicate = LLVMGetICmpPredicate(existingInst);
                return LLVMBuildICmp(builder, predicate, op1, op2, LLVMGetValueName(existingInst).getString());
            }
            case LLVMRet: {
                // ret i32 %val  or  ret void
                if (LLVMGetNumOperands(existingInst) == 0) {
                    return LLVMBuildRetVoid(builder);
                }
                LLVMValueRef value = getMappedOperand(existingInst, 0, operandMapping);
                return LLVMBuildRet(builder, value);
            }
            case LLVMBr: {
                // br i1 %cond, label %true, label %false  or  br label %dest
                int numOperands = LLVMGetNumOperands(existingInst);
                if (numOperands == 1) {
                    // Unconditional branch
                    LLVMBasicBlockRef dest = LLVMValueAsBasicBlock(getMappedOperand(existingInst, 0, operandMapping));
                    return LLVMBuildBr(builder, dest);
                } else if (numOperands == 3) {
                    // Conditional branch
                    LLVMValueRef cond = getMappedOperand(existingInst, 0, operandMapping);
                    LLVMBasicBlockRef trueDest = LLVMValueAsBasicBlock(getMappedOperand(existingInst, 1, operandMapping));
                    LLVMBasicBlockRef falseDest = LLVMValueAsBasicBlock(getMappedOperand(existingInst, 2, operandMapping));
                    return LLVMBuildCondBr(builder, cond, trueDest, falseDest);
                }
                throw new UnsupportedOperationException("Invalid branch instruction operands: " + numOperands);
            }
            default:
                throw new UnsupportedOperationException("Unsupported instruction opcode: " + opcode);
        }
    }

    /**
     * Gets the operand at the specified index, applying the operand mapping if available.
     */
    private LLVMValueRef getMappedOperand(
            LLVMValueRef inst,
            int index,
            Map<LLVMValueRef, LLVMValueRef> operandMapping) {
        LLVMValueRef operand = index < LLVMGetNumOperands(inst) ? LLVMGetOperand(inst, index) : inst;
        return operandMapping.getOrDefault(operand, operand);
    }
}
