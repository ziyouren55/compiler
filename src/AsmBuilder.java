import java.util.List;

public class AsmBuilder {
    private StringBuilder asm;
    private int labelCounter;

    public AsmBuilder() {
        this.asm = new StringBuilder();
        this.labelCounter = 0;
    }

    public String emitGlobalConstant(String name, String value) {
        return String.format(".section .rodata\n" +
                ".align 2\n" +
                ".global %s\n" +
                "%s:\n" +
                "    .word %s\n", name, name, value);
    }

    public String emitGlobalVariable(String name, String value) {
        return String.format("    .data\n%s:\n    .word %s\n", name, value);
    }

    public String emitLocalConstant(String name, String value, String reg) {
        return String.format("    li %s, %s\n", reg, value);
    }

    public String emitLocalVariable(String name, String value, String reg) {
        return String.format("    li %s, %s\n", reg, value);
    }

    public String emitFunction(String name, String body) {
        return String.format(".text\n.global %s\n%s:\n%s", name, name, body);
    }

    public String emitAssignment(String lval, String exp) {
        return String.format("    mv %s, %s\n", lval, exp);
    }

    public String emitIfStatement(String cond, String thenBlock, String elseBlock) {
        String elseLabel = genLabel("else");
        String endLabel = genLabel("endif");

        StringBuilder asm = new StringBuilder();
        asm.append(String.format("    beqz %s, %s\n", cond, elseLabel));
        asm.append(thenBlock);
        if (elseBlock != null) {
            asm.append(String.format("    j %s\n", endLabel));
            asm.append(String.format("%s:\n", elseLabel));
            asm.append(elseBlock);
            asm.append(String.format("%s:\n", endLabel));
        } else {
            asm.append(String.format("%s:\n", elseLabel));
        }
        return asm.toString();
    }

    public String emitReturn(String value) {
        if (value == null) {
            return "    ret\n";
        } else {
            return String.format("    mv a0, %s\n    ret\n", value);
        }
    }

    public String emitFunctionCall(String funcName, List<String> args) {
        StringBuilder asm = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            asm.append(String.format("    mv a%d, %s\n", i, args.get(i)));
        }
        asm.append(String.format("    call %s\n", funcName));
        return asm.toString();
    }

    public String emitStackFrame(int size) {
        return String.format("    addi sp, sp, -%d\n", size);
    }

    public String emitStackFrameRestore(int size) {
        return String.format("    addi sp, sp, %d\n", size);
    }

    public String emitStore(String value, String offset) {
        return String.format("    sw %s, %s(sp)\n", value, offset);
    }

    public String emitLoad(String dest, String offset) {
        return String.format("    lw %s, %s(sp)\n", dest, offset);
    }

    public String emitBinaryOperation(String op, String dest, String src1, String src2) {
        switch (op) {
            case "add":
                return String.format("    add %s, %s, %s\n", dest, src1, src2);
            case "sub":
                return String.format("    sub %s, %s, %s\n", dest, src1, src2);
            case "mul":
                return String.format("    mul %s, %s, %s\n", dest, src1, src2);
            case "div":
                return String.format("    div %s, %s, %s\n", dest, src1, src2);
            case "divu":
                return String.format("    divu %s, %s, %s\n", dest, src1, src2);
            case "rem":
                return String.format("    rem %s, %s, %s\n", dest, src1, src2);
            case "remu":
                return String.format("    remu %s, %s, %s\n", dest, src1, src2);
            default:
                throw new IllegalArgumentException("Unknown operation: " + op);
        }
    }

    public String emitLoadImmediate(String reg, String value) {
        return String.format("    li %s, %s\n", reg, value);
    }

    public String emitSystemCall() {
        return "    ecall\n";
    }

    public String emitLoadGlobal(String reg, String varName) {
        return String.format("    la %s, %s\n    lw %s, 0(%s)\n", reg, varName, reg, reg);
    }

    public String emitStoreGlobal(String reg, String varName) {
        return String.format("    la t4, %s\n    sw %s, 0(t4)\n", varName, reg);
    }

    private String genLabel(String prefix) {
        return prefix + "_" + (labelCounter++);
    }

    public String getAsm() {
        return asm.toString();
    }

    /**
     * 生成相等比较指令
     */
    public String emitEqual(String dest, String src1, String src2) {
        StringBuilder code = new StringBuilder();
        code.append(String.format("    xor %s, %s, %s\n", dest, src1, src2));
        code.append(String.format("    seqz %s, %s\n", dest, dest));
        return code.toString();
    }

    /**
     * 生成不等比较指令
     */
    public String emitNotEqual(String dest, String src1, String src2) {
        StringBuilder code = new StringBuilder();
        code.append(String.format("    xor %s, %s, %s\n", dest, src1, src2));
        code.append(String.format("    snez %s, %s\n", dest, dest));
        return code.toString();
    }

    /**
     * 生成小于比较指令
     */
    public String emitLessThan(String dest, String src1, String src2) {
        return String.format("    slt %s, %s, %s\n", dest, src1, src2);
    }

    /**
     * 生成大于比较指令
     */
    public String emitGreaterThan(String dest, String src1, String src2) {
        return String.format("    slt %s, %s, %s\n", dest, src2, src1);
    }

    /**
     * 生成小于等于比较指令
     */
    public String emitLessEqual(String dest, String src1, String src2) {
        StringBuilder code = new StringBuilder();
        code.append(String.format("    slt %s, %s, %s\n", dest, src2, src1));
        code.append(String.format("    xori %s, %s, 1\n", dest, dest));
        return code.toString();
    }

    /**
     * 生成大于等于比较指令
     */
    public String emitGreaterEqual(String dest, String src1, String src2) {
        StringBuilder code = new StringBuilder();
        code.append(String.format("    slt %s, %s, %s\n", dest, src1, src2));
        code.append(String.format("    xori %s, %s, 1\n", dest, dest));
        return code.toString();
    }

    /**
     * 生成条件跳转指令（不为零则跳转）
     */
    public String emitBranchNotZero(String cond, String targetLabel) {
        return String.format("    bnez %s, %s\n", cond, targetLabel);
    }

    /**
     * 生成条件跳转指令（为零则跳转）
     */
    public String emitBranchZero(String cond, String targetLabel) {
        return String.format("    beqz %s, %s\n", cond, targetLabel);
    }

    /**
     * 生成无条件跳转指令
     */
    public String emitJump(String targetLabel) {
        return String.format("    j %s\n", targetLabel);
    }

    // 处理zext指令 - 在RISC-V中可能不需要额外指令，只需移动即可
    public String emitZeroExtend(String dest, String src) {
        // 在RISC-V中，布尔值已经是整数，可能只需要复制
        return String.format("    mv %s, %s\n", dest, src);
    }
}
