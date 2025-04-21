import org.llvm4j.llvm4j.*;
import org.llvm4j.llvm4j.FunctionType;
import org.llvm4j.llvm4j.Function;
import org.llvm4j.llvm4j.Module;
import org.llvm4j.llvm4j.Type;
import org.llvm4j.optional.Option;
import org.llvm4j.llvm4j.IntPredicate;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;

public class LLVMIRVisitor extends SysYParserBaseVisitor<Value>
{
    private SymbolTableLLVM curScope;
    private SymbolTableLLVM globalScope;
    private StringBuilder log = new StringBuilder();
    private List<Type> paramsTyList = new ArrayList<>();
    private List<String> paramsNameList = new ArrayList<>();
    private Stack<BasicBlock> continueStack = new Stack<>();
    private Stack<BasicBlock> breakStack = new Stack<>();
    private Stack<List<Value>> rParamsStack = new Stack<>();
    private Function currentFunction;
    private int tmpCnt = 0;
    private boolean lastTerminatorGenerated = false;

    private final Context context = new Context();
    private final IRBuilder builder = context.newIRBuilder();
    private final Module mod = context.newModule("module");
    private final IntegerType i32 = context.getInt32Type();
    private final ConstantInt zero = i32.getConstant(0, false);

    public Module getMod()
    {
        return mod;
    }

    private void log(String message) {
        log.append(message).append("\n");
//        System.out.println(message);
    }

    private String genTmp(String prefix) {
        return prefix + "_" + (tmpCnt++);
    }

    // 辅助方法：从基本类型和维度列表构造嵌套的数组类型
    private Type buildArrayType(Type baseType, List<Value> dimensions) {
        Type type = baseType;
        // 倒序构造：先构造最内层数组，再构造外层数组
        for (int i = dimensions.size() - 1; i >= 0; i--) {
            int len = extractIntValue(dimensions.get(i)); // 提取整型常量值，例如 4, 再 3
            // context.getArrayType 用于构造固定大小的数组类型，例如 [len x type]
            type = context.getArrayType(type, len).unwrap();
        }
        return type;
    }

    private int extractIntValue(Value value) {
        if (value instanceof ConstantInt) {
            ConstantInt constInt = (ConstantInt) value;
            return (int) constInt.getSignExtendedValue(); // 返回常量的整型值
        }
        throw new IllegalArgumentException("期望传入 ConstantInt 类型的值，但实际类型不匹配");
    }

    @Override
    public Value visitCompUnit(SysYParser.CompUnitContext ctx)
    {
        // 假设 compUnit 由若干 Decl 和 FuncDef 组成
        globalScope = new SymbolTableLLVM(null);
        curScope = globalScope;

        // 遍历所有的声明（变量、常量等）
        for (int i = 0;i < ctx.getChildCount();i++) {
            try(Value value = visit(ctx.getChild(i)))
            {
                log("finish one compUnit");
            };  // 处理每个声明

        }

        return null;
    }

    @Override
    public Value visitDecl(SysYParser.DeclContext ctx)
    {
        if (ctx.constDecl() != null)
        {
            return visitConstDecl(ctx.constDecl());
        }
        else if (ctx.varDecl() != null)
        {
            return visitVarDecl(ctx.varDecl());
        }

        return null;
    }

    @Override
    public Value visitConstDecl(SysYParser.ConstDeclContext ctx)
    {
        for (SysYParser.ConstDefContext constDefCtx : ctx.constDef()) {
            try(Value value = visitConstDef(constDefCtx);)
            {
                log("finish one constDecl");
            }
        }
        return null;
    }

    @Override
    public Value visitConstDef(SysYParser.ConstDefContext ctx)
    {
        String constName = ctx.IDENT().getText();

        // 计算数组维度（如果有的话）
        List<Value> dimensions = new ArrayList<>();
        // 注意：如果有多个 '[' constExp ']'，则 ctx.constExp() 返回包含所有对应子节点
        for (SysYParser.ConstExpContext expCtx : ctx.constExp())
        {
            // 计算每个维度大小，假设返回的值为整数常量
            Value dimVal = visitConstExp(expCtx);
            dimensions.add(dimVal);
        }

        // 计算常量的初始值
        Value constInitValue = visitConstInitVal(ctx.constInitVal());

        // 根据是否有数组维度区分标量和数组
        if (dimensions.isEmpty())
        {
            var globalVar = mod.addGlobalVariable(constName, i32, Option.empty()).unwrap();
            globalVar.setInitializer((Constant) constInitValue);
            // 标量常量
            globalScope.put(constName, globalVar);
            log("Defined scalar constant: " + constName);
        }

        return constInitValue;
    }

    @Override
    public Value visitConstInitVal(SysYParser.ConstInitValContext ctx)
    {
        if(!ctx.constInitVal().isEmpty())
        {
            for (int i = 0;i < ctx.constInitVal().size(); i++)
                try(Value value = visitConstInitVal(ctx.constInitVal(i));)
                {
                    log("finish one constInitVal");
                }
        }
        else
        {
            // 否则认为是单个常量表达式
            return visitConstExp(ctx.constExp());
        }
        return null;
    }

    @Override
    public Value visitVarDecl(SysYParser.VarDeclContext ctx)
    {
        for (int i = 0; i < ctx.varDef().size(); i ++) {
            try(Value value = visitVarDef(ctx.varDef(i)))
            {
                log("finish one varDef");// 依次visit def，即依次visit c=4 和 d=5
            }
        }
        // return super.visitVarDecl(ctx);

        return null;
    }

    @Override
    public Value visitVarDef(SysYParser.VarDefContext ctx)
    {
        String varName = ctx.IDENT().getText();
          // 处理初始化表达式
        Value initVal = (ctx.ASSIGN() != null)
        ? visitInitVal(ctx.initVal())
        : i32.getConstant(0, false);

         // 2. 如果 currentFunction==null，就当做全局变量处理
        if (currentFunction == null) {
            // a) 在模块里添加全局变量
            var gVar = mod
              .addGlobalVariable(varName, context.getInt32Type(), Option.empty())
              .unwrap();
            // b) 设置初始值
            gVar.setInitializer((Constant) initVal);
            // c) 保存到你的全局符号表
            globalScope.put(varName, gVar);
            return gVar;
        }

        // 局部变量需要调用 builder.buildAlloca 分配内存
        Value localVar = builder.buildAlloca(i32, Option.of(varName));
        curScope.put(varName, localVar);
        if (ctx.ASSIGN() != null) {
            builder.buildStore(localVar, initVal);
        }

        return initVal;
    }

    @Override
    public Value visitInitVal(SysYParser.InitValContext ctx)
    {
        if (ctx.exp() != null)
        {
            return visitExp(ctx.exp());
        }
        else
        {
            for (int i = 0; i < ctx.initVal().size(); i++)
                try (Value value = visitExp(ctx.exp());)
                {
                    log("finish one exp");

                }
        }

        return null;
    }

    @Override
    public Value visitFuncDef(SysYParser.FuncDefContext ctx)
    {
        curScope = new SymbolTableLLVM(curScope);
        // 获取函数名
        String funcName = ctx.IDENT().getText();

        // 确定函数返回类型：例如 "int" 对应 IntType，"void" 对应 VoidType
        Type retType;
        String typeStr = ctx.getChild(0).getText();
        if ("int".equals(typeStr)) {
            retType = i32;
        } else {
            retType = context.getVoidType(); // 假设存在VoidType类
        }

         try(Value value = visitFuncFParams(ctx.funcFParams()))
        {
            log("finish one FParams");
        }

        FunctionType ft = context
            .getFunctionType(retType, paramsTyList.toArray(new Type[0]), false);
        Function func = mod.addFunction(funcName, ft);
        currentFunction = func;

        // 创建 entry block
        BasicBlock entry = context.newBasicBlock(funcName + "Entry");
        func.addBasicBlock(entry);
        builder.positionAfter(entry);

         // 给每个参数做 alloca + store，并存进符号表
        for (int i = 0; i < paramsNameList.size(); i++) {
            Type   ty   = paramsTyList.get(i);
            String name = paramsNameList.get(i);
            Value  arg  = func.getParameter(i).unwrap();

            Value alloca = builder
                .buildAlloca(ty, Option.of(name));

            builder.buildStore(alloca,arg);
            curScope.put(name, alloca);
        }

        // 构造函数类型，存储返回类型和参数类型列表
        globalScope.put(funcName,func);

        // 访问函数体（block），进行进一步的语义检查
        try(Value value = visitBlock(ctx.block()))
        {
            log("finish one block");
        }

        //todo 出现了两个ret
        if(retType.getRef().equals(context.getVoidType().getRef()))
        {
            builder.buildReturn(Option.empty());
        }

        curScope = curScope.getParent();

        return func;
    }

    @Override
    public Value visitFuncFParams(SysYParser.FuncFParamsContext ctx)
    {
        paramsNameList.clear();
        paramsTyList.clear();

        if(ctx == null)
            return null;

        // 遍历所有的形参
        for (SysYParser.FuncFParamContext paramCtx : ctx.funcFParam()) {
            try(Value param = visitFuncFParam(paramCtx);)
            {
                log("finish one fParam");
            }
        }

        return null;
    }

    @Override
    public Value visitFuncFParam(SysYParser.FuncFParamContext ctx)
    {
        Type ty = i32;
        String name = ctx.IDENT().getText();

        paramsTyList.add(ty);
        paramsNameList.add(name);

        return null;
    }

    @Override
    public Value visitBlock(SysYParser.BlockContext ctx)
    {
        curScope = new SymbolTableLLVM(curScope);
        for (SysYParser.BlockItemContext blockItem : ctx.blockItem())
        {
            try (Value value = visitBlockItem(blockItem))
            {
                log("finish one blockItem");
            }

        }
        curScope = curScope.getParent();
        return null;
    }

    @Override
    public Value visitBlockItem(SysYParser.BlockItemContext ctx)
    {
        if (ctx.decl() != null)
        {
            return visitDecl(ctx.decl());
        }
        else if (ctx.stmt() != null)
        {
            return visitStmt(ctx.stmt());
        }
        return null;
    }

    @Override
    public Value visitStmt(SysYParser.StmtContext ctx)
    {
        lastTerminatorGenerated = false;

        if (ctx.ASSIGN() != null)
        {
            // Handle assignment statement
            return visitAssignStmt(ctx);
        }
        else if (ctx.getChild(0).getText().equals("return"))
        {
            // Handle return statement
            return visitReturnStmt(ctx);
        }
        else if (ctx.block() != null)
        {
            // Handle block
            return visitBlock(ctx.block());
        }
        else if (ctx.getChild(0).getText().equals("if"))
        {
            // Handle if statement
            return visitIfStmt(ctx);
        }
        else if (ctx.getChild(0).getText().equals("while"))
        {
            // Handle while statement
            return visitWhileStmt(ctx);
        }
        else if(ctx.getChild(0).getText().equals("break"))
        {
            //tba
            // 从栈中获取当前最内层循环的 mergeBlock
            BasicBlock innerLoopMergeBlock = breakStack.peek();
            // 构造跳转到最内层循环的 mergeBlock（结束循环）
            builder.buildBranch(innerLoopMergeBlock);
            lastTerminatorGenerated = true;
        }
        else if(ctx.getChild(0).getText().equals("continue"))
        {
            //tba
            // 从栈中获取当前最内层循环的条件判断基本块（condBlock）
            BasicBlock innerLoopCondBlock = continueStack.peek();
            // 构造跳转到最内层循环的 condBlock（进行条件判断）
            builder.buildBranch(innerLoopCondBlock);  // 使用 buildBranch 跳回条件判断
            lastTerminatorGenerated = true;
        }

        else if (ctx.exp() != null)
        {
            // Handle expression statement
            return visitExp(ctx.exp());
        }
        return null;
    }

    // Handle assignment statement (LVal = Exp)
    private Value visitAssignStmt(SysYParser.StmtContext ctx)
    {
        // Load the left-hand value (LVal) to obtain a pointer
        String leftValName = ctx.lVal().IDENT().getText();
        Value leftVal = curScope.find(leftValName);

        // Evaluate the right-hand expression
        Value rightVal = visitExp(ctx.exp());

        // Store the right-hand value into the left-hand value's location
        builder.buildStore(leftVal, rightVal);
        return null;
    }

    private Value visitIfStmt(SysYParser.StmtContext ctx)
    {

        /* 1. 计算条件并建三个基本块 */
        Value cond = visitCond(ctx.cond());

        BasicBlock trueBlk = context.newBasicBlock("if_true");
        BasicBlock falseBlk = context.newBasicBlock("if_false");
        BasicBlock mergeBlk = context.newBasicBlock("if_merge");

        currentFunction.addBasicBlock(trueBlk);
        currentFunction.addBasicBlock(falseBlk);
        currentFunction.addBasicBlock(mergeBlk);

        builder.buildConditionalBranch(cond, trueBlk, falseBlk);

        /* 2. 生成 true 分支 */
        builder.positionAfter(trueBlk);
        lastTerminatorGenerated = false;
        visitStmt(ctx.stmt(0));
        boolean trueHasTerm = lastTerminatorGenerated;
        if (!trueHasTerm)
            builder.buildBranch(mergeBlk);

        /* 3. 生成 false 分支（可能没有） */
        builder.positionAfter(falseBlk);
        lastTerminatorGenerated = false;
        if (ctx.stmt().size() > 1)
        {
            visitStmt(ctx.stmt(1));
        }
        boolean falseHasTerm = lastTerminatorGenerated;
        if (!falseHasTerm)
            builder.buildBranch(mergeBlk);

        /* 4. merge 块收尾 */
        if (trueHasTerm && falseHasTerm)
        {
            // 两条分支都已经 return / branch 走完，
            // merge 块没人跳进来，但必须有 terminator
            builder.positionAfter(mergeBlk);
            builder.buildUnreachable();
            // 之后没有插入点；由调用者（visitStmt 上层）再设置
        }
        else
        {
            builder.positionAfter(mergeBlk);
        }
        return null;
    }

    private Value visitWhileStmt(SysYParser.StmtContext ctx)
    {
        // 创建基本块
        BasicBlock condBlock = context.newBasicBlock("while_cond");
        BasicBlock bodyBlock = context.newBasicBlock("while_body");
        BasicBlock mergeBlock = context.newBasicBlock("while_merge");


        currentFunction.addBasicBlock(condBlock);
        currentFunction.addBasicBlock(bodyBlock);
        currentFunction.addBasicBlock(mergeBlock);

         // 2) push 两个栈
        continueStack.push(condBlock);    // continue 回到这里
        breakStack.push(mergeBlock);      // break 跳到这里


        // 初始跳转到条件判断
        builder.buildBranch(condBlock);
        builder.positionAfter(condBlock);

        // 计算条件表达式
        Value cond = visitCond(ctx.cond());

        // 根据条件决定是否跳转到循环体或退出
        builder.buildConditionalBranch(cond, bodyBlock, mergeBlock);

        // 处理循环体
        builder.positionAfter(bodyBlock);
        visitStmt(ctx.stmt(0));  // 循环体的语句
        builder.buildBranch(condBlock);  // 返回到条件判断

        // 处理合并后的代码（循环退出后）
        builder.positionAfter(mergeBlock);

        // pop 两个栈
        continueStack.pop();
        breakStack.pop();

        return null;
    }

    // Handle return statement
    private Value visitReturnStmt(SysYParser.StmtContext ctx)
    {
        Value returnVal = null;
        if (ctx.exp() != null)
        {
            // If there is an expression, evaluate it
            returnVal = visitExp(ctx.exp());
        }

        // Return the evaluated value or void
        builder.buildReturn(Option.of(returnVal));
        lastTerminatorGenerated = true;
        return null;
    }

    @Override
    public Value visitExp(SysYParser.ExpContext ctx)
    {
        return visitAddExp(ctx.addExp());
    }

    @Override
    public Value visitCond(SysYParser.CondContext ctx)
    {
        Value intVal = visitLOrExp(ctx.lOrExp());            // i32
        return builder.buildIntCompare(
            IntPredicate.NotEqual,
            intVal, zero,                                   // i32 != 0 -> i1
            Option.of("cond")
        );
    }

    @Override
    public Value visitLVal(SysYParser.LValContext ctx)
    {
        String name = ctx.IDENT().getText();
        Value ptr = curScope.find(name);

        return builder.buildLoad(ptr, Option.of(name + "_val"));
    }

    @Override
    public Value visitPrimaryExp(SysYParser.PrimaryExpContext ctx)
    {
        if (ctx.lVal() != null) {
            return visitLVal(ctx.lVal());
        }
        // 如果是括号表达式，如 (exp)
        if (ctx.exp() != null) {
            return visitExp(ctx.exp());
        }

            /* ---------- 整数字面量 ---------- */
        String text = ctx.INTEGER_CONST().getText();
        long raw;                       // 先用 long 把 32bit 全兜住
        if (text.startsWith("0x") || text.startsWith("0X"))
            raw = Long.parseUnsignedLong(text.substring(2), 16);
        else if (text.startsWith("0") && text.length() > 1)
            raw = Long.parseUnsignedLong(text.substring(1), 8);
        else
            raw = Long.parseLong(text); // 十进制可以直接用有符号

        /* 只取低 32 位，然后告诉 LLVM4J：这是“带符号”常量 */
        int bits32     = (int) (raw & 0xFFFF_FFFFL);
        boolean signed = true;          // 必须 sign‑extend，才能处理最高位=1 的情况
        return i32.getConstant(bits32, signed);   // 绝不会返回 null
    }

    @Override
    public Value visitUnaryExp(SysYParser.UnaryExpContext ctx)
    {
        if (ctx.primaryExp() != null)
            return visitPrimaryExp(ctx.primaryExp());
        if (ctx.IDENT() != null)
        {
            // 调用
            Function fn = mod.getFunction(ctx.IDENT().getText()).unwrap();
            visitFuncRParams(ctx.funcRParams());
            List<Value> args =rParamsStack.pop();

            Type fnType = fn.getValueType();
            if(fnType.getAsString().startsWith("void"))
                return builder.buildCall(fn, args.toArray(new Value[0]), Option.empty());
            return builder.buildCall(fn, args.toArray(new Value[0]), Option.of(genTmp("call")));
        }
        // 一元
        String op = ctx.unaryOp().getText();
        Value v = visitUnaryExp(ctx.unaryExp());
        switch (op)
        {
            case "+":
                return v;
            case "-":
                // 处理负号一元操作
                if (v instanceof ConstantInt)
                {
                    long val = ((ConstantInt) v).getSignExtendedValue();
                    val = -val;  // Java 里先计算
                    int bits32 = (int) (val & 0xFFFF_FFFFL);
                    return i32.getConstant(bits32, true);  // 返回负数常量
                }
                return builder.buildIntSub(zero, v, WrapSemantics.Unspecified, Option.of(genTmp("neg")));  // 非常量时生成减法指令
            case "!":
                Value cmp = builder.buildIntCompare(IntPredicate.Equal, v, zero, Option.of(genTmp("eq")));
                return builder.buildZeroExt(cmp, i32, Option.of(genTmp("zext")));
            default:
                throw new RuntimeException(op);
        }
    }

    @Override
    public Value visitFuncRParams(SysYParser.FuncRParamsContext ctx)
    {
        List<Value> paramsList = new ArrayList<>();
        if(ctx == null)
        {
            rParamsStack.push(paramsList);
            return null;
        }

        for (SysYParser.ExpContext expCtx : ctx.exp()) {
            Value value = visitExp(expCtx);
            paramsList.add(value);
        }
        rParamsStack.push(paramsList);
        return null;
    }

    @Override
    public Value visitMulExp(SysYParser.MulExpContext ctx)
    {
        // 先处理第一个 unaryExp
        Value acc = visitUnaryExp(ctx.unaryExp(0));
        // 依次处理后续的 (* | / | %) unaryExp
        for (int i = 1; i < ctx.unaryExp().size(); i++)
        {
            // 运算符总是在子节点序号 2*i-1 处
            String op = ctx.getChild(2 * i - 1).getText();
            Value rhs = visitUnaryExp(ctx.unaryExp(i));
            switch (op)
            {
                case "*":
                    acc = builder.buildIntMul(
                        acc,
                        rhs,
                        WrapSemantics.Unspecified,
                        Option.of(genTmp("mul"))
                    );
                    break;
                case "/":
                    acc = builder.buildSignedDiv(
                        acc,
                        rhs,
                        false,
                        Option.of(genTmp("div"))
                    );
                    break;
                case "%":
                    acc = builder.buildSignedRem(
                        acc,
                        rhs,
                        Option.of(genTmp("rem"))
                    );
                    break;
            }
        }
        return acc;
    }

    @Override
    public Value visitAddExp(SysYParser.AddExpContext ctx)
    {
        // 1. 先计算第一个 mulExp
        Value acc = visitMulExp(ctx.mulExp(0));
        // 2. 依次处理后续的 (‘+’ | ‘-’) mulExp
        for (int i = 1; i < ctx.mulExp().size(); i++)
        {
            // 操作符位于子节点序号 2*i-1
            String op = ctx.getChild(2 * i - 1).getText();
            Value rhs = visit(ctx.mulExp(i));
            if (op.equals("+"))
            {
                // buildIntAdd(lhs, rhs, semantics, name)
                acc = builder.buildIntAdd(
                    acc,
                    rhs,
                    WrapSemantics.Unspecified,
                    Option.of(genTmp("add"))
                );
            }
            else
            {
                // buildIntSub(lhs, rhs, semantics, name)
                acc = builder.buildIntSub(
                    acc,
                    rhs,
                    WrapSemantics.Unspecified,
                    Option.of(genTmp("sub"))
                );
            }
        }
        return acc;
    }

    // relExp : addExp (('<'|'>'|'<='|'>=') addExp)* ;
    @Override
    public Value visitRelExp(SysYParser.RelExpContext ctx)
    {
        // 先生成第一个 addExp 的 i32 值
        Value acc = visitAddExp(ctx.addExp(0));
        // 依次处理每个比较
        for (int i = 1; i < ctx.addExp().size(); i++)
        {
            Value rhs = visitAddExp(ctx.addExp(i));
            String op = ctx.getChild(2 * i - 1).getText();  // 比较符在子节点 2*i-1
            IntPredicate pred;
            switch (op)
            {
                case "<":
                    pred = IntPredicate.SignedLessThan;
                    break;
                case ">":
                    pred = IntPredicate.SignedGreaterThan;
                    break;
                case "<=":
                    pred = IntPredicate.SignedLessEqual;
                    break;
                default:
                    pred = IntPredicate.SignedGreaterEqual;
                    break;
            }
            // 生成 i1 比较
            Value cmp = builder.buildIntCompare(pred, acc, rhs, Option.of(genTmp("cmp"))

            );
            // 扩展到 i32
            acc = builder.buildZeroExt(cmp, context.getInt32Type(), Option.of(genTmp("zext"))
            );
        }
        return acc;
    }
// :contentReference[oaicite:0]{index=0}


    // eqExp : relExp (('=='|'!=') relExp)* ;
    @Override
    public Value visitEqExp(SysYParser.EqExpContext ctx)
    {
        Value acc = visitRelExp(ctx.relExp(0));
        for (int i = 1; i < ctx.relExp().size(); i++)
        {
            Value rhs = visitRelExp(ctx.relExp(i));
            String op = ctx.getChild(2 * i - 1).getText();
            IntPredicate pred = op.equals("==")
                ? IntPredicate.Equal
                : IntPredicate.NotEqual;
            Value cmp = builder.buildIntCompare(
                pred, acc, rhs, Option.of(genTmp("cmp"))
            );
            acc = builder.buildZeroExt(
                cmp, context.getInt32Type(), Option.of(genTmp("zext"))
            );
        }
        return acc;
    }
// :contentReference[oaicite:1]{index=1}


    // lAndExp : eqExp ('&&' eqExp)* ;
    @Override
    public Value visitLAndExp(SysYParser.LAndExpContext ctx)
    {
        // 首先生成第一个 eqExp 的值，并转成 i1 真假标志
        Value accVal = visitEqExp(ctx.eqExp(0));
        Value accBool = builder.buildIntCompare(
            IntPredicate.NotEqual, accVal, zero, Option.of(genTmp("neq"))
        );
        // 对后续每个 '&& eqExp' 继续做 bitwise and
        for (int i = 1; i < ctx.eqExp().size(); i++)
        {
            Value rhsVal = visitEqExp(ctx.eqExp(i));
            Value rhsBool = builder.buildIntCompare(
                IntPredicate.NotEqual, rhsVal, zero, Option.of(genTmp("neq"))
            );
            accBool = builder.buildLogicalAnd(
                accBool, rhsBool, Option.of(genTmp("and"))
            );
        }
        // 最后把 i1 结果扩展回 i32
        return builder.buildZeroExt(
            accBool, context.getInt32Type(), Option.of(genTmp("zext"))
        );
    }
// :contentReference[oaicite:2]{index=2}


    // lOrExp : lAndExp ('||' lAndExp)* ;
    @Override
    public Value visitLOrExp(SysYParser.LOrExpContext ctx)
    {
        Value accVal = visitLAndExp(ctx.lAndExp(0));
        Value accBool = builder.buildIntCompare(
            IntPredicate.NotEqual, accVal, zero, Option.of(genTmp("neq"))
        );
        for (int i = 1; i < ctx.lAndExp().size(); i++)
        {
            Value rhsVal = visitLAndExp(ctx.lAndExp(i));
            Value rhsBool = builder.buildIntCompare(
                IntPredicate.NotEqual, rhsVal, zero, Option.of(genTmp("neq"))
            );
            accBool = builder.buildLogicalOr(
                accBool, rhsBool, Option.of(genTmp("or"))
            );
        }
        return builder.buildZeroExt(
            accBool, context.getInt32Type(), Option.of(genTmp("zext"))
        );
    }
// :contentReference[oaicite:3]{index=3}


    // constExp : addExp ;
    @Override
    public Value visitConstExp(SysYParser.ConstExpContext ctx)
    {
        // 常量表达式直接走 addExp 的实现
        return visit(ctx.addExp());
    }



}
