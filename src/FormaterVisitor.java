import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;

public class FormaterVisitor extends SysYParserBaseVisitor<String>
{
    private OutputHelper outputHelper = new OutputHelper();
    private SymbolTable curScope = new SymbolTable(null);
    private SymbolTable funcFParamsScope = null;
    private List<Type> paramsTyList = new ArrayList<>();
    private Type curFuncRetTy = null;
    private int indentLevel = 0;
    private boolean firstFunction = true;
    private StringBuilder sb = new StringBuilder();

    // 返回当前缩进字符串，每级 4 个空格
    private String indent()
    {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < indentLevel; i++)
        {
            s.append("    ");
        }
        return s.toString();
    }

    // 入口：访问整个编译单元
    @Override
    public String visitCompUnit(SysYParser.CompUnitContext ctx)
    {
        // 假设 compUnit 由若干 Decl 和 FuncDef 组成
        SymbolTable globalScope = new SymbolTable(null);
        curScope = globalScope;

        // 遍历所有的声明（变量、常量等）
        for (int i = 0;i < ctx.getChildCount();i++) {
            visit(ctx.getChild(i));  // 处理每个声明
        }


        return String.valueOf(outputHelper.isWrong);
    }

    @Override
    public String visitDecl(SysYParser.DeclContext ctx)
    {
        if (ctx.constDecl() != null)
        {
            return visit(ctx.constDecl());
        }
        else if (ctx.varDecl() != null)
        {
            return visit(ctx.varDecl());
        }

        return "";
    }

    @Override
    public String visitConstDecl(SysYParser.ConstDeclContext ctx)
    {
        for (SysYParser.ConstDefContext constDefCtx : ctx.constDef()) {
            visit(constDefCtx);
        }
        return "";
    }

    @Override
    public String visitConstDef(SysYParser.ConstDefContext ctx)
    {
        String constName = ctx.IDENT().getText();

        if (curScope.find(constName) != null) {
//            outputHelper.printSemanticError(ErrorType.REDEFINED_VAR, ctx.IDENT().getSymbol().getLine(), constName);
            return "error " + ErrorType.REDEFINED_VAR.getErrorCode();
        }

        if(ctx.constExp() == null)
        {
            visit(ctx.constInitVal());
            curScope.put(constName,IntType.getI32());
        }
        else
        {
            List<Integer> dims = new ArrayList<>();
            for (SysYParser.ConstExpContext expCtx : ctx.constExp()) {
                visit(expCtx);
                Object dimVal = evaluateConstExpr(expCtx);
                dims.add((Integer) dimVal);
            }
            // 构造数组类型：基类型为 int，数组维度由 dims 给出
            ArrayType arrType = new ArrayType(IntType.getI32(), dims.size());
            curScope.put(constName, arrType);
        }

        return "";
    }

    private Object evaluateConstExpr(SysYParser.ConstExpContext expCtx)
    {
        return 0;
    }

    @Override
    public String visitConstInitVal(SysYParser.ConstInitValContext ctx)
    {
        if(!ctx.constInitVal().isEmpty())
        {
            for (int i = 0;i < ctx.constInitVal().size(); i++)
                visit(ctx.constInitVal(i));
        }
        else
        {
            // 否则认为是单个常量表达式
            return visit(ctx.constExp());
        }

        return "";
    }

    @Override
    public String visitVarDecl(SysYParser.VarDeclContext ctx)
    {
        for (int i = 0; i < ctx.varDef().size(); i ++) {
            visit(ctx.varDef(i)); // 依次visit def，即依次visit c=4 和 d=5
        }
        // return super.visitVarDecl(ctx);

        return "";
    }

    @Override
    public String visitVarDef(SysYParser.VarDefContext ctx)
    {
        String varName = ctx.IDENT().getText(); // c or d
        if (curScope.localFind(varName) != null) {
//            outputHelper.printSemanticError(ErrorType.REDEFINED_VAR, ctx.IDENT().getSymbol().getLine(),
//                    ctx.IDENT().getText());
            return "error "+ErrorType.REDEFINED_VAR.getErrorCode();
        }

        if (ctx.constExp().isEmpty()) {     //非数组
            curScope.put(varName, IntType.getI32());
        } else { // 数组
            ArrayType arrType = new ArrayType(IntType.getI32(),ctx.constExp().size());
            curScope.put(varName, arrType);
        }
        if (ctx.ASSIGN() != null) {     // 包含定义语句
            String type = visitInitVal(ctx.initVal()); // 访问定义语句右侧的表达式，如c=4右侧的4

        }
        return "";
    }

    @Override
    public String visitInitVal(SysYParser.InitValContext ctx)
    {
        //TODO 不知道这里要不要错误识别
        if (ctx.exp() != null)
        {
            visit(ctx.exp());
        }
        else
        {
            for (int i = 0;i < ctx.initVal().size();i++)
                visit(ctx.initVal(i));
        }

        return "";
    }

    // 处理函数定义，要求：函数定义前（除入口函数外）空一行
    @Override
    public String visitFuncDef(SysYParser.FuncDefContext ctx)
    {
        // 获取函数名
        String funcName = ctx.IDENT().getText();

        // 检查当前作用域中是否已存在该函数（重定义错误）
        if (curScope.localFind(funcName) != null) {
//            outputHelper.printSemanticError(ErrorType.REDEFINED_FUNC,
//                ctx.IDENT().getSymbol().getLine(),
//                "Redefined function: " + funcName);
            return "error "+ErrorType.REDEFINED_FUNC.getErrorCode();

        }

        // 确定函数返回类型：例如 "int" 对应 IntType，"void" 对应 VoidType
        Type retType;
        String typeStr = ctx.getChild(0).getText();
        if ("int".equals(typeStr)) {
            retType = IntType.getI32();
        } else if ("void".equals(typeStr)) {
            retType = VoidType.getVoidType(); // 假设存在VoidType类
        } else {
            // 其他返回类型可以根据需求处理
            retType = null;
        }

        curFuncRetTy = retType;

        curScope = new SymbolTable(curScope);

        paramsTyList = new ArrayList<>();
        if (ctx.funcFParams() != null) {
            // 假设 processFuncFParams 是一个将函数形参解析为 List<Type> 的辅助方法
            visit(ctx.funcFParams());
        }

        // 构造函数类型，存储返回类型和参数类型列表
        FunctionType functionType = new FunctionType(retType, paramsTyList);

        // 访问函数体（block），进行进一步的语义检查
        visit(ctx.block());

        curScope = curScope.getParent();

        // 将函数类型加入全局符号表
        curScope.put(funcName, functionType);

        paramsTyList = new ArrayList<>();

        return "";
    }

    // 形参列表：逗号后添加一个空格
    @Override
    public String visitFuncFParams(SysYParser.FuncFParamsContext ctx)
    {
        paramsTyList = new ArrayList<>();

        // 遍历所有的形参
        for (SysYParser.FuncFParamContext paramCtx : ctx.funcFParam()) {
            visitFuncFParam(paramCtx);
        }

        return "";
    }

    @Override
    public String visitFuncFParam(SysYParser.FuncFParamContext ctx)
    {
        String typeStr = ctx.getChild(0).getText();
        Type paramType = null;
        // 判断是否为数组参数（此处仅考虑一维数组）
        if (ctx.getChildCount() > 2 && ctx.getChild(2).getText().equals("[")) {
            // 数组参数：使用 ArrayType 封装 int 类型和数组维度信息
            paramType = new ArrayType(IntType.getI32(), ctx.L_BRACKT().size());
        }

        if (typeStr.equals("int") && ctx.getChildCount() == 2) {
            paramType = IntType.getI32();
        }
        // 获取形参名
        String paramName = ctx.IDENT().getText();

        // 检查当前作用域中是否已经存在该形参
        if (curScope.find(paramName) != null) {
//            outputHelper.printSemanticError(ErrorType.REDEFINED_VAR,
//                ctx.IDENT().getSymbol().getLine(), paramName);
            // 按要求只保留第一次出现的形参
            return "error "+ErrorType.REDEFINED_VAR.getErrorCode();

        }

        // 将形参添加到当前作用域中
        curScope.put(paramName, paramType);
        // 将形参类型添加到参数列表中，供构造函数类型使用
        paramsTyList.add(paramType);

        return "";
    }

    // 处理代码块：花括号规则和缩进
    @Override
    public String visitBlock(SysYParser.BlockContext ctx)
    {
        for (SysYParser.BlockItemContext blockItem : ctx.blockItem()) {
            String bis = blockItem.getText();
            visit(blockItem);
        }

        return "";
    }

    // 处理块内项：声明或语句
    @Override
    public String visitBlockItem(SysYParser.BlockItemContext ctx)
    {
        if (ctx.decl() != null)
        {
            visit(ctx.decl());
        }
        else if (ctx.stmt() != null)
        {
            visit(ctx.stmt());
        }
        return "";
    }

    // 处理语句，根据不同类型分别格式化
    @Override
    public String visitStmt(SysYParser.StmtContext ctx)
    {
         // 如果是块语句，直接调用 visitBlock 处理
        if (ctx.block() != null) {
            curScope = new SymbolTable(curScope);
            visit(ctx.block());
            curScope = curScope.getParent();
        }
        // 如果是 if 语句：形如 if (exp) stmt [else stmt]
        else if (ctx.getChild(0).getText().equals("if")) {
            // 访问 if 条件表达式
            visit(ctx.cond());
            // 访问 if 分支对应的语句
            visit(ctx.stmt(0));
            // 如果存在 else 分支，访问 else 分支
            if (ctx.getChildCount() > 5) { // 典型的 if 语句结构：if ( exp ) stmt else stmt
                visit(ctx.stmt(1));
            }

        }
        // 如果是 while 语句：形如 while (exp) stmt
        else if (ctx.getChild(0).getText().equals("while")) {
            // 访问 while 条件表达式
            visit(ctx.cond());
            // 访问 while 循环体
            visit(ctx.stmt(0));

        }
        // 如果是 return 语句：形如 return [exp] ;
        else if (ctx.getChild(0).getText().equals("return")) {
            // 若 return 后跟表达式，访问该表达式
            Type retTY = new VoidType();
            if (ctx.exp() != null) {
                String type = visit(ctx.exp());
                if(type.equals("int"))
                {
                    retTY = IntType.getI32();
                }
            }
            if(!retTY.equals(curFuncRetTy))
            {
                outputHelper.printSemanticError(ErrorType.FUNC_RETURN_TYPE_MISMATCH,
                    ctx.SEMICOLON().getSymbol().getLine(),"return type error");
                return "error "+ErrorType.FUNC_RETURN_TYPE_MISMATCH.getErrorCode();
            }

        }
        else if(ctx.lVal() != null)
        {
            String lValTY = visit(ctx.lVal());
            String expTY = visit(ctx.exp());

            if(lValTY.equals("func"))
            {
                outputHelper.printSemanticError(ErrorType.ASSIGN_TO_NON_VAR,
                    ctx.ASSIGN().getSymbol().getLine(),ctx.lVal().getText());
                return "error "+ErrorType.ASSIGN_TO_NON_VAR.getErrorCode();
            }

            if(!lValTY.equals(expTY) && !(lValTY.startsWith("error") || expTY.startsWith("error")))
            {
//                outputHelper.printSemanticError(ErrorType.MISMATCH_ASSIGN,
//                    ctx.ASSIGN().getSymbol().getLine(),"missMatch");
                return "error "+ErrorType.MISMATCH_ASSIGN.getErrorCode();
            }
        }
        // 其它情况，按表达式语句处理（也可能是空语句）
        else {
            if (ctx.exp() != null) {
                visit(ctx.exp());
            }

        }

        return "";
    }

    // 以下对表达式部分做简化处理（这里只对加减表达式做简单处理，二元运算符两侧空格）
    @Override
    public String visitExp(SysYParser.ExpContext ctx)
    {
        return visit(ctx.addExp());
    }

    // 处理条件表达式（假设 cond 对应 LOrExp）
    @Override
    public String visitCond(SysYParser.CondContext ctx)
    {
        visit(ctx.lOrExp());
        return "";
    }

    @Override
    public String visitLVal(SysYParser.LValContext ctx)
    {
        String varName = ctx.IDENT().getText();
    // 从当前符号表中查找变量类型
        Type varType = curScope.find(varName);
        if (varType == null) {
            // 未定义变量，报告错误（假设错误类型为 ErrorType.UNDEFINED_VAR）
            outputHelper.printSemanticError(ErrorType.UNDEFINED_VAR,
                ctx.IDENT().getSymbol().getLine(), varName);
            return "error "+ErrorType.UNDEFINED_VAR.getErrorCode();
        }
        if (varType instanceof FunctionType)
        {
//            outputHelper.printSemanticError(ErrorType.ASSIGN_TO_NON_VAR
//                ,ctx.IDENT().getSymbol().getLine(), varName);
//            return "error "+ ErrorType.ASSIGN_TO_NON_VAR.getErrorCode();
            if(ctx.getChildCount() > 1 && ctx.getChild(1).getText().equals("["))
                outputHelper.printSemanticError(ErrorType.NON_ARRAY_SUBSCRIPT,
                    ctx.IDENT().getSymbol().getLine(),varName);
            return "func";
        }

        if(varType instanceof IntType)
        {
            if(ctx.getChildCount()>1 && ctx.getChild(1).getText().equals("["))
                outputHelper.printSemanticError(ErrorType.NON_ARRAY_SUBSCRIPT,
                    ctx.IDENT().getSymbol().getLine(),varName);
            return "int";
        }

        if(varType instanceof ArrayType)
        {
            int indexCount = ctx.exp().size();
            int remainDims = ((ArrayType) varType).getNumElements() - indexCount;
            if (remainDims < 0)
            {
                outputHelper.printSemanticError(ErrorType.NON_ARRAY_SUBSCRIPT,
                    ctx.IDENT().getSymbol().getLine(), varName);
                return "error arr";
            }

            if (remainDims == 0)
                return "int";
            else
                return "arr" + remainDims;
        }
        return "";
    }

    @Override
    public String visitPrimaryExp(SysYParser.PrimaryExpContext ctx)
    {
        // 如果是 LVal，直接访问 LVal
        if (ctx.lVal() != null) {
            return visit(ctx.lVal());
        }
        // 如果是括号表达式，如 (exp)
        else if (ctx.exp() != null) {
            return visit(ctx.exp());
        }
        // 否则认为是数字常量（例如 "123"）
        else {
            // 对于数字常量，通常认为类型为 int，这里直接返回空字符串
            String primaryExpName = ctx.INTEGER_CONST().getText();
            return "int";
        }
    }

    @Override
    public String visitUnaryExp(SysYParser.UnaryExpContext ctx)
    {
        // 情形1：unaryExp 为 primaryExp
        if (ctx.primaryExp() != null)
        {
            return visit(ctx.primaryExp());
        }
        // 情形2：unaryExp 为函数调用形式，即 IDENT '(' [funcRParams] ')'
        else if (ctx.getChild(0).getText().matches("[a-zA-Z_][a-zA-Z0-9_]*") && ctx.getChild(1).getText().equals("("))
        {
            String funcName = ctx.getChild(0).getText();
            // 查找函数在符号表中的类型
            Type funcType = curScope.find(funcName);
            if (funcType == null)
            {
//                outputHelper.printSemanticError(ErrorType.UNDEFINED_FUNC,
//                    ctx.IDENT().getSymbol().getLine(), funcName);
                return "error "+ErrorType.UNDEFINED_FUNC.getErrorCode();
            }
            if (!(funcType instanceof FunctionType))
            {
                outputHelper.printSemanticError(ErrorType.VAR_USED_AS_FUNC,
                    ctx.IDENT().getSymbol().getLine(), funcName);
                return "error "+ErrorType.VAR_USED_AS_FUNC.getErrorCode();
            }
            // 如果存在参数列表，处理函数调用的参数
            paramsTyList = new ArrayList<>();
            if (ctx.funcRParams() != null)
            {
                visit(ctx.funcRParams());
            }
            List<Type> formalParams = ((FunctionType) funcType).getParamsType();
            List<Type> actualParams = paramsTyList;

            if (actualParams.size() != formalParams.size())
            {
                outputHelper.printSemanticError(ErrorType.FUNC_PARAM_MISMATCH, ctx.getStart().getLine(), funcName);
            }
            else
            {
                // 逐个比较参数类型
                for (int i = 0; i < actualParams.size(); i++)
                {
                    if (!actualParams.get(i).equals(formalParams.get(i)))
                    {
                        outputHelper.printSemanticError(ErrorType.FUNC_PARAM_MISMATCH, ctx.getStart().getLine(), funcName);
                    }
                }
            }
            paramsTyList = new ArrayList<>();
            // 这里可以进一步检查实际参数与形式参数是否匹配（本示例中省略）
            if(((FunctionType) funcType).getRetType() instanceof IntType)
            {
                return "int";
            }
            else if(((FunctionType) funcType).getRetType() instanceof VoidType)
                return "void";
            return "func";

        }
        // 情形3：unaryExp 为一元操作表达式，如 '!'、'+'、'-' unaryExp
        else
        {
            // 第一个子节点为操作符，第二个子节点为后续的 unaryExp
            return visit(ctx.unaryExp());
            // 根据具体操作符可能需要进行类型检查，
            // 此处仅简单返回操作数的检查结果
        }
    }

    @Override
    public String visitFuncRParams(SysYParser.FuncRParamsContext ctx)
    {
        paramsTyList = new ArrayList<>();
        for (SysYParser.ExpContext expCtx : ctx.exp()) {
            String type =  visit(expCtx);
            if(type.equals("int"))
                paramsTyList.add(IntType.getI32());
            else if(type.startsWith("arr"))
            {
                int dim = Integer.parseInt(type.substring(3));
                paramsTyList.add(new ArrayType(IntType.getI32(),dim));
            }
        }
        return "";
    }

    @Override
    public String visitMulExp(SysYParser.MulExpContext ctx)
    {
        //todo 返回值被忽略，应考虑
        // 处理第一个操作数
        String type = visit(ctx.unaryExp(0)); // 假设返回类型为 "int"（或其它类型的字符串标识）
        String prevExp = ctx.unaryExp(0).getText();
        // 遍历 (('*' | '/' | '%') unaryExp)* 部分
        // 注意：ctx.unaryExp() 的数量比操作符多 1，每个操作符位于相应的位置上
        for (int i = 1; i < ctx.unaryExp().size(); i++)
        {
            // 获取操作符：该操作符位于子节点位置 2*i-1（因为解析树中交替排列：unaryExp, operator, unaryExp, operator, ...）
            String operator = ctx.getChild(2 * i - 1).getText();
            // 处理下一个操作数
            String postType = visit(ctx.unaryExp(i));
            String postExp = ctx.unaryExp(i).getText();
            // 简单类型检查：假设乘法运算要求操作数均为 int
            if (!type.equals(postType) )
            {
                TerminalNode opNode = (TerminalNode) ctx.getChild(2 * i - 1);
                // 如果类型不匹配，则报告错误：Invalid operator usage
                // 这里假设 ctx.getChild(2*i-1) 为包含操作符的节点，其行号作为错误行号
//                outputHelper.printSemanticError(ErrorType.INVALID_OPERATOR,
//                    opNode.getSymbol().getLine(), operator);
                return "error "+ ErrorType.INVALID_OPERATOR.getErrorCode();
            }

            // 如果类型检查通过，运算结果依然为 int
            prevExp = postExp; // 这里仅为示例，实际项目中可构造更复杂的类型对象
        }

        return type;
    }

    @Override
    public String visitAddExp(SysYParser.AddExpContext ctx)
    {
        // 先访问第一个乘法表达式，得到初始类型
        String resultType = visit(ctx.mulExp(0));
        // 遍历所有的加减操作，每个操作符位于解析树中相应位置
        for (int i = 1; i < ctx.mulExp().size(); i++)
        {
            // 运算符通常在子节点中位于 2*i-1 位置
            TerminalNode opNode = (TerminalNode) ctx.getChild(2 * i - 1);
            // 访问右侧的乘法表达式
            String rightType = visit(ctx.mulExp(i));
            // 检查左右操作数是否均为 int 类型
            if (!resultType.equals(rightType) || !resultType.equals("int"))
            {
//                outputHelper.printSemanticError(ErrorType.INVALID_OPERATOR, opNode.getSymbol().getLine(), opNode.getText());
                return "error "+ErrorType.INVALID_OPERATOR.getErrorCode();

            }
            // 加减运算的结果仍为 int 类型
            resultType = "int";
        }
        return resultType;
    }

    @Override
    public String visitRelExp(SysYParser.RelExpContext ctx)
    {
        for (SysYParser.AddExpContext addExpContext : ctx.addExp())
            visit(addExpContext);
        return "";
    }

    @Override
    public String visitEqExp(SysYParser.EqExpContext ctx)
    {
        for (SysYParser.RelExpContext relExpContext : ctx.relExp())
            visit(relExpContext);
        return "";
    }

    @Override
    public String visitLAndExp(SysYParser.LAndExpContext ctx)
    {
        for (SysYParser.EqExpContext eqExpContext : ctx.eqExp())
            visit(eqExpContext);
        return "";
    }

    @Override
    public String visitLOrExp(SysYParser.LOrExpContext ctx)
    {
        for (SysYParser.LAndExpContext lAndExpContext : ctx.lAndExp())
            visit(lAndExpContext);
        return "";
    }

    private String matchSimilarExp(ParserRuleContext ctx, List<String> ops)
    {
        if (ctx.getChildCount() == 1)
        {
            return visit(ctx.getChild(0));
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < ctx.getChildCount(); i++)
        {
            String part = ctx.getChild(i).getText();
            if (ops.contains(part))
            {
                result.append(" " + part + " ");
            }
            else
            {
                result.append(visit(ctx.getChild(i)));
            }
        }

        return result.toString();
    }

    @Override
    public String visitConstExp(SysYParser.ConstExpContext ctx)
    {
        return visit(ctx.addExp());
    }

    private int getArrayDimension(Type type) {
    int dimension = 0;
    while (type instanceof ArrayType) {
        dimension++;
        type = ((ArrayType) type).getContained();
    }
    return dimension;
}

    // 对于其他表达式、乘除模、关系、逻辑表达式等可按类似方式递归实现
    // 为简化示例，此处不再一一展开

    // 默认处理（对于未覆盖的规则，直接返回其文本）
//    @Override
//    public String visitChildren(RuleNode node)
//    {
//        String funcName = ctx.IDENT().getText();
//        if (curScope.find(funcName) != null) {
//            OutputHelper.printSemanticError(ErrorType.REDEFINED_FUNC, ctx.IDENT().getSymbol().getLine(), funcName);
//            return null;
//        }
//
//        // 处理函数返回类型及参数类型
//        Type retType = IntType.getI32(); // 假设返回类型为int
//        List<Type> paramTypes = new ArrayList<>();
//        // 假设有形参
//        paramTypes.add(IntType.getI32());
//
//        // 将函数信息加入符号表
//        FunctionType funcType = new FunctionType(retType, paramTypes);
//        curScope.put(funcName, funcType);
//
//        // 处理函数体
//        visit(ctx.block());
//
//        StringBuilder result = new StringBuilder();
//        for (int i = 0; i < node.getChildCount(); i++)
//        {
//            result.append(node.getChild(i).getText());
//        }
//        return result.toString();
//    }
}
