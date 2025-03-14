import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.RuleNode;

import java.util.ArrayList;
import java.util.List;

public class FormaterVisitor extends SysYParserBaseVisitor<String>
{
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
        for (int i = 0; i < ctx.getChildCount(); i++)
        {
            ParseTree child = ctx.getChild(i);
            String childStr = visit(child);
            if (childStr != null && !childStr.isEmpty())
            {
                sb.append(childStr.trim());

                if (i < ctx.getChildCount() - 1)
                {
                    sb.append("\n");
                    if (ctx.getChild(i + 1) instanceof SysYParser.FuncDefContext)
                        sb.append("\n");
                }
            }
        }
        return sb.toString().trim();
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
        return "Decl miss\nDecl miss\nDecl miss\n";
    }

    @Override
    public String visitConstDecl(SysYParser.ConstDeclContext ctx)
    {
        StringBuilder result = new StringBuilder();
        result.append("const ");
        result.append(ctx.bType().getText() + " ");
        for (int i = 0; i < ctx.constDef().size(); i++)
        {
            result.append(visit(ctx.constDef(i)));
            if (i != ctx.constDef().size() - 1)
            {
                result.append(", ");
            }
        }

        result.append(";");

        return result.toString();
    }

    @Override
    public String visitConstDef(SysYParser.ConstDefContext ctx)
    {
        StringBuilder result = new StringBuilder();
        result.append(ctx.IDENT().getText());
        if (ctx.constExp() != null)
        {
            for (int i = 0; i < ctx.constExp().size(); i++)
            {
                result.append("[" + visit(ctx.constExp(i)) + "]");
            }
        }
        result.append(" = ");
        result.append(visit(ctx.constInitVal()));

        return result.toString();
    }

    @Override
    public String visitConstInitVal(SysYParser.ConstInitValContext ctx)
    {
        if (ctx.constExp() != null)
            return visit(ctx.constExp());
        else if (ctx.constInitVal() != null)
        {
            StringBuilder result = new StringBuilder();
            result.append("{");
            for (int i = 0; i < ctx.constInitVal().size(); i++)
            {
                result.append(visit(ctx.constInitVal(i)));
                if (i != ctx.constInitVal().size() - 1)
                {
                    result.append(", ");
                }
            }
            result.append("}");

            return result.toString();
        }
        return "ConstInitVal miss";
    }

    @Override
    public String visitVarDecl(SysYParser.VarDeclContext ctx)
    {
        StringBuilder result = new StringBuilder();
        result.append(ctx.bType().getText()).append(" ");
        for (int i = 0; i < ctx.varDef().size(); i++)
        {
            result.append(visit(ctx.varDef(i)));
            if (i != ctx.varDef().size() - 1)
            {
                result.append(", ");
            }
        }
        result.append(";");

        return result.toString();
    }

    @Override
    public String visitVarDef(SysYParser.VarDefContext ctx)
    {
        StringBuilder result = new StringBuilder();
        result.append(ctx.IDENT().getText());
        for (int i = 0; i < ctx.constExp().size(); i++)
        {
            result.append("[").append(visit(ctx.constExp(i))).append("]");
        }
        if (ctx.initVal() != null)
        {
            result.append(" = ").append(visit(ctx.initVal()));
        }

        return result.toString();
    }

    @Override
    public String visitInitVal(SysYParser.InitValContext ctx)
    {
        if (ctx.exp() != null)
        {
            return visit(ctx.exp());
        }
        else
        {
            StringBuilder result = new StringBuilder();
            result.append("{");
            for (int i = 0; i < ctx.initVal().size(); i++)
            {
                result.append(visit(ctx.initVal(i)));
                if (i != ctx.initVal().size() - 1)
                {
                    result.append(", ");
                }
            }
            result.append("}");

            return result.toString();
        }
    }

    // 处理函数定义，要求：函数定义前（除入口函数外）空一行
    @Override
    public String visitFuncDef(SysYParser.FuncDefContext ctx)
    {
        StringBuilder result = new StringBuilder();
        if (!firstFunction)
        {
            result.append("\n"); // 除第一函数外，在函数定义前添加空行
        }
        firstFunction = false;
        // 格式化函数头：例如 "int main(int a, int b)"，注意返回类型与函数名之间有一个空格
        String funcHeader = ctx.funcType().getText() + " " + ctx.IDENT().getText() + "(";
        if (ctx.funcFParams() != null)
        {
            funcHeader += visit(ctx.funcFParams());
        }
        funcHeader += ")";
        result.append(indent() + funcHeader + " ");
        // 函数体：Block
        result.append(visit(ctx.block()));
        return result.toString();
    }

    // 形参列表：逗号后添加一个空格
    @Override
    public String visitFuncFParams(SysYParser.FuncFParamsContext ctx)
    {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < ctx.funcFParam().size(); i++)
        {
            result.append(visit(ctx.funcFParam(i)));
            if (i != ctx.funcFParam().size() - 1)
            {
                result.append(", ");
            }
        }
        return result.toString();
    }

    @Override
    public String visitFuncFParam(SysYParser.FuncFParamContext ctx)
    {
        // 例如："int a" 或 "int a[]" 或 "int a[][Exp]"
        StringBuilder param = new StringBuilder();
        param.append(ctx.bType().getText() + " ");
        param.append(ctx.IDENT().getText());
        // 处理可能出现的数组部分
        if (ctx.getChildCount() > 2)
        {
            for (int i = 2; i < ctx.getChildCount(); i++)
            {
                param.append(ctx.getChild(i).getText());
            }
        }
        return param.toString();
    }

    // 处理代码块：花括号规则和缩进
    @Override
    public String visitBlock(SysYParser.BlockContext ctx)
    {
        StringBuilder blockSb = new StringBuilder();
        blockSb.append("{\n");
        indentLevel++;
        for (SysYParser.BlockItemContext item : ctx.blockItem())
        {
            String itemStr = visit(item);
            if (itemStr != null && !itemStr.isEmpty())
            {
                blockSb.append(indent() + itemStr.trim());
                blockSb.append("\n");
            }
        }
        indentLevel--;
        blockSb.append(indent() + "}");
        return blockSb.toString();
    }

    // 处理块内项：声明或语句
    @Override
    public String visitBlockItem(SysYParser.BlockItemContext ctx)
    {
        if (ctx.decl() != null)
        {
            return visit(ctx.decl());
        }
        else if (ctx.stmt() != null)
        {
            return visit(ctx.stmt());
        }
        return "";
    }

    // 处理语句，根据不同类型分别格式化
    @Override
    public String visitStmt(SysYParser.StmtContext ctx)
    {
        // 以下仅为简单示例：处理 if/while/return/赋值或表达式语句
        if (ctx.getText().startsWith("{"))
        {
            // 块语句直接递归
            return visit(ctx.block());
        }
        else if (ctx.getText().startsWith("if"))
        {
            // if 语句可能带 else
            StringBuilder s = new StringBuilder();
            s.append("if (");
            s.append(visit(ctx.cond()));
            s.append(")");
            // 如果 if 后面不是块，需要在新行增加缩进
            String stmt;
            if (!ctx.stmt(0).getText().trim().startsWith("{"))
            {
                indentLevel++;
                stmt = "\n" + indent() + visit(ctx.stmt(0));
                indentLevel--;
            }
            else
            {
                stmt = visit(ctx.stmt(0));
                s.append(" ");
            }
            s.append(stmt);

            if (ctx.getChildCount() > 5)
            { // 存在 else 部分
                s.append("\n" + indent() + "else");
                String stmt1;
                // 如果 else 部分既不是块，也不是 if 语句，则增加缩进
                if (!ctx.stmt(1).getText().trim().startsWith("{") &&
                    !ctx.stmt(1).getText().trim().startsWith("if"))
                {
                    indentLevel++;
                    stmt1 = "\n" + indent() + visit(ctx.stmt(1));
                    indentLevel--;
                }
                else
                {
                    stmt1 = visit(ctx.stmt(1));
                    s.append(" ");
                }
                s.append(stmt1);
            }
            return s.toString();
        }
        else if (ctx.getText().startsWith("while"))
        {
            StringBuilder s = new StringBuilder();
            s.append("while (");
            s.append(visit(ctx.cond()));
            s.append(")");
            String stmt;
            // 如果后面的语句不是一个块，则在调用 visit 之前先增加缩进级别
            if (!ctx.stmt(0).getText().trim().startsWith("{"))
            {
                indentLevel++;
                stmt = "\n" + indent() + visit(ctx.stmt(0));
                indentLevel--;
            }
            else
            {
                stmt = visit(ctx.stmt(0));
                s.append(" ");
            }
            s.append(stmt);
            return s.toString();
        }
        else if (ctx.getText().startsWith("return"))
        {
            String ret = "return";
            if (ctx.exp() != null)
            {
                ret += " " + visit(ctx.exp());
            }
            ret += ";";
            return ret;
        }
        else if (ctx.getText().startsWith("break") || ctx.getText().startsWith("continue"))
        {
            return visit(ctx.getChild(0)) + ";";
        }
        else if (ctx.lVal() != null)
        {
            return visit(ctx.lVal()) + " = " + visit(ctx.exp()) + ";";
        }
        else
        {
            // 对于赋值语句或单独表达式语句：直接拼接并在末尾加分号
            String expStr = "";
            if (ctx.getChildCount() > 1)
            {
                expStr = visit(ctx.getChild(0));
            }
            return expStr + ";";
        }
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
        return visit(ctx.lOrExp());
    }

    @Override
    public String visitLVal(SysYParser.LValContext ctx)
    {
        StringBuilder result = new StringBuilder();
        result.append(ctx.IDENT().getText());
        if (ctx.exp() != null)
        {
            for (int i = 0; i < ctx.exp().size(); i++)
            {
                result.append("[").append(visit(ctx.exp(i))).append("]");
            }
        }

        return result.toString();
    }

    @Override
    public String visitPrimaryExp(SysYParser.PrimaryExpContext ctx)
    {
        StringBuilder result = new StringBuilder();
        if (ctx.exp() != null)
        {
            result.append("(").append(visit(ctx.exp())).append(")");
        }
        else if (ctx.lVal() != null)
        {
            result.append(visit(ctx.lVal()));
        }
        else if (ctx.INTEGER_CONST() != null)
        {
            result.append(ctx.INTEGER_CONST().getText());
        }
        else
        {
            result.append("PrimaryExp miss");
        }

        return result.toString();
    }

    @Override
    public String visitUnaryExp(SysYParser.UnaryExpContext ctx)
    {
        StringBuilder result = new StringBuilder();
        if (ctx.primaryExp() != null)
        {
            result.append(visit(ctx.primaryExp()));
        }
        else if (ctx.IDENT() != null)
        {
            result.append(ctx.IDENT().getText());
            result.append("(");
            if (ctx.funcRParams() != null)
            {
                result.append(visit(ctx.funcRParams()));
            }
            result.append(")");
        }
        else if (ctx.unaryExp() != null && ctx.unaryOp() != null)
        {
            result.append(ctx.unaryOp().getText());
            result.append(visit(ctx.unaryExp()));
        }
        else
        {
            result.append("UnaryExp miss");
        }

        return result.toString();
    }

    @Override
    public String visitFuncRParams(SysYParser.FuncRParamsContext ctx)
    {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < ctx.exp().size(); i++)
        {
            result.append(visit(ctx.exp(i)));
            if (i != ctx.exp().size() - 1)
            {
                result.append(", ");
            }
        }
        return result.toString();
    }

    @Override
    public String visitMulExp(SysYParser.MulExpContext ctx)
    {
        List<String> ops = new ArrayList<>();
        ops.add("*");
        ops.add("/");
        ops.add("%");
        return matchSimilarExp(ctx, ops);
    }

    @Override
    public String visitAddExp(SysYParser.AddExpContext ctx)
    {
        List<String> ops = new ArrayList<>();
        ops.add("+");
        ops.add("-");
        return matchSimilarExp(ctx, ops);
    }

    @Override
    public String visitRelExp(SysYParser.RelExpContext ctx)
    {
        List<String> ops = new ArrayList<>();
        ops.add("<");
        ops.add(">");
        ops.add("<=");
        ops.add(">=");
        return matchSimilarExp(ctx, ops);
    }

    @Override
    public String visitEqExp(SysYParser.EqExpContext ctx)
    {
        List<String> ops = new ArrayList<>();
        ops.add("==");
        ops.add("!=");
        return matchSimilarExp(ctx, ops);
    }

    @Override
    public String visitLAndExp(SysYParser.LAndExpContext ctx)
    {
        List<String> ops = new ArrayList<>();
        ops.add("&&");
        return matchSimilarExp(ctx, ops);
    }

    @Override
    public String visitLOrExp(SysYParser.LOrExpContext ctx)
    {
        List<String> ops = new ArrayList<>();
        ops.add("||");
        return matchSimilarExp(ctx, ops);
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

    // 对于其他表达式、乘除模、关系、逻辑表达式等可按类似方式递归实现
    // 为简化示例，此处不再一一展开

    // 默认处理（对于未覆盖的规则，直接返回其文本）
    @Override
    public String visitChildren(RuleNode node)
    {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < node.getChildCount(); i++)
        {
            result.append(node.getChild(i).getText());
        }
        return result.toString();
    }
}
