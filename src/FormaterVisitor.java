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


    @Override
    public String visitChildren(RuleNode node) {
        StringBuilder result = new StringBuilder();

        // 遍历所有子节点
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree child = node.getChild(i);
            String childText = child.getText().trim();

            // 判断节点类型并处理
            if (childText.equals("{")) {
                // 处理花括号：开括号开始新的代码块
                result.append("{\n");
                indentLevel++;  // 增加缩进
            } else if (childText.equals("}")) {
                // 处理花括号：闭括号结束当前代码块
                indentLevel--;  // 减少缩进
                result.append("\n" + indent() + "}");
            } else if (child instanceof RuleNode) {
                // 对 RuleNode 类型的子节点进行递归访问
                result.append(visit(child));
            } else {
                // 处理其他节点内容（如运算符、标识符等）
                if (childText.matches("[\\+\\-\\*/%=<>&|!]+")) {
                    // 二元运算符，两边加空格
                    result.append(" " + childText + " ");
                } else if (childText.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                    // 变量名、标识符等直接添加
                    result.append(childText);
                } else {
                    // 处理普通文本（如常量、数字等）
                    result.append(childText);
                }
            }

            // 在节点之间添加适当的空格
            if (i < node.getChildCount() - 1) {
                result.append(" ");
            }
        }
        return result.toString();
    }


    // 对于其他表达式、乘除模、关系、逻辑表达式等可按类似方式递归实现
    // 为简化示例，此处不再一一展开

    // 默认处理（对于未覆盖的规则，直接返回其文本）
}
