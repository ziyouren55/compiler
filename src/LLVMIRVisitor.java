import org.llvm4j.llvm4j.*;
import org.llvm4j.llvm4j.FunctionType;
import org.llvm4j.llvm4j.Function;
import org.llvm4j.llvm4j.Module;
import org.llvm4j.llvm4j.Type;
import org.llvm4j.optional.Option;
import org.llvm4j.llvm4j.IntPredicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

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
    private BasicBlock condTrueBlock;
    private BasicBlock condFalseBlock;
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

        // 计算常量的初始值
        Value constInitValue = visitConstInitVal(ctx.constInitVal());

        // 判断是否为局部常量或全局常量
        if (curScope == globalScope)
        {
            // 如果当前作用域是全局作用域，处理为全局常量
            GlobalVariable globalVar = mod.addGlobalVariable(constName, i32, Option.empty()).unwrap();
            globalVar.setInitializer((Constant) constInitValue);
            globalScope.put(constName, globalVar); // 保存到全局符号表
            log("Defined global constant: " + constName);
        }
        else
        {
            // 否则，假设是局部常量
            // 在函数内部为局部常量分配内存
            Value localVar = builder.buildAlloca(i32, Option.of(constName));
            builder.buildStore(localVar, constInitValue);
            curScope.put(constName, localVar);  // 保存到局部符号表
            log("Defined local constant: " + constName);
        }

        return constInitValue;
    }

    @Override
    public Value visitConstInitVal(SysYParser.ConstInitValContext ctx)
    {
        if (curScope == globalScope)
        {
            // 全局常量需要编译时计算
            return visitConstExp(ctx.constExp());
        }
        else
        {
            // 局部常量使用运行时计算
            return visitAddExp(ctx.constExp().addExp());
        }
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
            GlobalVariable gVar = mod
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

        if(retType.getRef().equals(context.getVoidType().getRef()) && !lastTerminatorGenerated)
        {
            builder.buildReturn(Option.empty());
            lastTerminatorGenerated = true;
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
        BasicBlock trueBlk = context.newBasicBlock("if_true");
        BasicBlock falseBlk = context.newBasicBlock("if_false");
        BasicBlock mergeBlk = context.newBasicBlock("if_merge");

        currentFunction.addBasicBlock(trueBlk);
        currentFunction.addBasicBlock(falseBlk);
        currentFunction.addBasicBlock(mergeBlk);

        condTrueBlock = trueBlk;
        condFalseBlock = falseBlk;

        genLOr(ctx.cond().lOrExp(), trueBlk, falseBlk);

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

        condTrueBlock = bodyBlock;
        condFalseBlock = mergeBlock;

        // 初始跳转到条件判断
        builder.buildBranch(condBlock);
        builder.positionAfter(condBlock);

        genLOr(ctx.cond().lOrExp(), bodyBlock, mergeBlock);

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
            BasicBlock rhsBlcTrue = context.newBasicBlock(genTmp("blc_true"));
            currentFunction.addBasicBlock(rhsBlcTrue);
            builder.buildConditionalBranch(accBool,rhsBlcTrue,condFalseBlock);
            builder.positionAfter(rhsBlcTrue);

            Value rhsVal = visitEqExp(ctx.eqExp(i));
            Value rhsBool = builder.buildIntCompare(
                IntPredicate.NotEqual, rhsVal, zero, Option.of(genTmp("neq"))
            );
            accBool = builder.buildLogicalAnd(
                accBool, rhsBool, Option.of(genTmp("and"))
            );
        }
        // 最后把 i1 结果扩展回 i32
        return builder.buildZeroExt(accBool, context.getInt32Type(), Option.of(genTmp("zext")));
    }
// :contentReference[oaicite:2]{index=2}

    /**
 * 生成对 CondContext 的短路分支：
 *   如果为真就跳到 trueBlock，否则跳到 falseBlock
 */
    private void genCond(SysYParser.CondContext ctx,
                         BasicBlock trueBlock,
                         BasicBlock falseBlock)
    {
        genLOr(ctx.lOrExp(), trueBlock, falseBlock);
    }

    /**
     * 对 lAndExp 生成短路 &&：如果都真则跳 trueBlock，一假就跳 falseBlock
     */
    private void genLAnd(SysYParser.LAndExpContext ctx,
                         BasicBlock trueBlock,
                         BasicBlock falseBlock)
    {
        List<SysYParser.EqExpContext> parts = ctx.eqExp();
        // 对每个 eqExp(0..n-2)：
        //   eval part, 真就进 nextBlock，假就直跳 falseBlock
        for (int i = 0; i < parts.size() - 1; i++)
        {
            BasicBlock next = context.newBasicBlock(genTmp("and.rhs"));
            currentFunction.addBasicBlock(next);

            // 1) 计算第 i 个子表达式
            Value v = visitEqExp(parts.get(i));
            Value cond = builder.buildIntCompare(
                IntPredicate.NotEqual, v, zero, Option.of(genTmp("neq")));

            // 2) 短路分支：真 -> next，假 -> falseBlock
            builder.buildConditionalBranch(cond, next, falseBlock);

            // 3) 在 next 继续
            builder.positionAfter(next);
        }

        // 最后一个子表达式：真 -> trueBlock，假 -> falseBlock
        Value vLast = visitEqExp(parts.get(parts.size() - 1));
        Value condLast = builder.buildIntCompare(
            IntPredicate.NotEqual, vLast, zero, Option.of(genTmp("neq")));
        builder.buildConditionalBranch(condLast, trueBlock, falseBlock);
    }


    /**
     * 对 lOrExp 生成短路 ||：一真就跳 trueBlock，都假才跳 falseBlock
     */
    private void genLOr(SysYParser.LOrExpContext ctx,
                        BasicBlock trueBlock,
                        BasicBlock falseBlock)
    {
        List<SysYParser.LAndExpContext> parts = ctx.lAndExp();
        // 对每个 lAndExp(0..n-2)：
        //   eval part, 真就直跳 trueBlock，假就进 nextBlock
        for (int i = 0; i < parts.size() - 1; i++)
        {
            BasicBlock next = context.newBasicBlock(genTmp("or.rhs"));
            currentFunction.addBasicBlock(next);

            // 1) 短路 OR：先判第 i 个 and 子表达式
            genLAnd(parts.get(i), trueBlock, next);

            // 2) 在 next 继续判剩下的
            builder.positionAfter(next);
        }

        // 最后一个 and 子表达式：真 -> trueBlock，假 -> falseBlock
        genLAnd(parts.get(parts.size() - 1), trueBlock, falseBlock);
    }

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
            BasicBlock rhsBlcFalse = context.newBasicBlock(genTmp("blc_rhs"));
            currentFunction.addBasicBlock(rhsBlcFalse);
            builder.buildConditionalBranch(accBool,condTrueBlock,rhsBlcFalse);
            builder.positionAfter(rhsBlcFalse);

            Value rhsVal = visitLAndExp(ctx.lAndExp(i));
            Value rhsBool = builder.buildIntCompare(
                IntPredicate.NotEqual, rhsVal, zero, Option.of(genTmp("neq"))
            );
            accBool = builder.buildLogicalOr(
                accBool, rhsBool, Option.of(genTmp("or")));
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
        int v = computeConstAddExp(ctx.addExp());
        return i32.getConstant(v, false);
    }

    private int computeConstAddExp(SysYParser.AddExpContext ctx)
    {
        int acc = computeConstMulExp(ctx.mulExp(0));
        for (int i = 1; i < ctx.mulExp().size(); i++)
        {
            int rhs = computeConstMulExp(ctx.mulExp(i));
            String op = ctx.getChild(2 * i - 1).getText();
            if (op.equals("+")) acc += rhs;
            else acc -= rhs;
        }
        return acc;
    }

    private int computeConstMulExp(SysYParser.MulExpContext ctx)
    {
        int acc = computeConstUnaryExp(ctx.unaryExp(0));
        for (int i = 1; i < ctx.unaryExp().size(); i++)
        {
            int rhs = computeConstUnaryExp(ctx.unaryExp(i));
            String op = ctx.getChild(2 * i - 1).getText();
            switch (op)
            {
                case "*":
                    acc *= rhs;
                    break;
                case "/":
                    acc /= rhs;
                    break;
                case "%":
                    acc %= rhs;
                    break;
            }
        }
        return acc;
    }

    private int computeConstUnaryExp(SysYParser.UnaryExpContext ctx)
    {
        if (ctx.primaryExp() != null)
        {
            return computeConstPrimaryExp(ctx.primaryExp());
        }
        if (ctx.unaryOp() != null)
        {
            int v = computeConstUnaryExp(ctx.unaryExp());
            switch (ctx.unaryOp().getText())
            {
                case "+":
                    return +v;
                case "-":
                    return -v;
                // 常量折叠里一般不会出现 '!'，如果出现可以抛异常或处理
            }
        }
        // 不应该到这里
        throw new IllegalStateException("Unexpected ConstUnaryExp");
    }

    private int computeConstPrimaryExp(SysYParser.PrimaryExpContext ctx)
    {
        if (ctx.INTEGER_CONST() != null)
        {
            String text = ctx.INTEGER_CONST().getText();
            if (text.startsWith("0x") || text.startsWith("0X")) return Integer.parseUnsignedInt(text.substring(2), 16);
            if (text.startsWith("0") && text.length() > 1) return Integer.parseUnsignedInt(text.substring(1), 8);
            return Integer.parseInt(text);
        }
        if (ctx.lVal() != null)
        {
            // 从符号表里拿到全局常量的初始化器
            // 假设 globalScope.find(name) 返回的 Value 是一个 GlobalVariable
            GlobalVariable gv = (GlobalVariable) globalScope.find(ctx.lVal().IDENT().getText());
            Constant c = gv.getInitializer().unwrap();
            String lit = c.getAsString().substring(4);          // 有时会直接是 "2"
            int v = Integer.parseInt(lit);
            return v;
        }
        if (ctx.exp() != null)
        {
            return computeConstAddExp(ctx.exp().addExp());
        }
        throw new IllegalStateException("Unexpected ConstPrimaryExp");
    }

}
