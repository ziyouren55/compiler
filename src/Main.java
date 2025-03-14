import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.IOException;

public class Main {

    public static void main(String[] args) throws IOException
    {
        if (args.length < 1) {
            System.err.println("input path is required");
        }
        String source = args[0];
        CharStream input = CharStreams.fromFileName(source);

        CommonTokenStream tokens = lexerAnalysis(input);
        ParseTree tree = parserAnalysis(tokens);

    }

    public static ParseTree parserAnalysis(CommonTokenStream tokens)
    {
        // 构造 Parser
        SysYParser sysYParser = new SysYParser(tokens);
        MyErrorListener errorListener = new MyErrorListener();
        sysYParser.removeErrorListeners();
        sysYParser.addErrorListener(errorListener);

        // 调用语法分析的起始规则（例如 compUnit）
        ParseTree tree = sysYParser.compUnit();

        // 如果语法分析阶段有错误，则打印错误信息并退出
        if (errorListener.hasErrors()) {
            errorListener.printParserErrorInformation();
        }
        else
        {
            FormaterVisitor formatter = new FormaterVisitor();
            String formattedCode = formatter.visit(tree);
            System.out.println(formattedCode);
            //shan
            System.out.println(check(formattedCode));
        }
        return tree;
    }

    public static CommonTokenStream lexerAnalysis(CharStream input)
    {
        SysYLexer sysYLexer = new SysYLexer(input);

        MyErrorListener errorListener = new MyErrorListener();
        sysYLexer.removeErrorListeners();
        sysYLexer.addErrorListener(errorListener);

        CommonTokenStream tokens = new CommonTokenStream(sysYLexer);
        tokens.fill(); // 预加载所有 token

        if(errorListener.hasErrors())
        {
            errorListener.printLexerErrorInformation();
        }
//        else
//        {
//            for (Token token : tokens.getTokens())
//            {
//                String tokenStr = SysYLexer.VOCABULARY.getSymbolicName(token.getType()) + " " + token.getText() + " at Line " + token.getLine() + ".";
//                System.err.println(tokenStr);
//            }
//        }

        return tokens;
    }

    public static boolean check(String result)
    {
        String exp = "int main() {\n" +
            "    int a = 0;\n" +
            "    if (a > 0) {\n" +
            "        return -!a;\n" +
            "    }\n" +
            "    else if (a < 1) {\n" +
            "        if (1 == 2) \n" +
            "            return 0;\n" +
            "        else if (n == x) {\n" +
            "        }\n" +
            "        else {\n" +
            "            return 0;\n" +
            "        }\n" +
            "        return +a;\n" +
            "    }\n" +
            "    else {\n" +
            "        if (a == 0) {\n" +
            "        }\n" +
            "        else if (a == a) {\n" +
            "        }\n" +
            "    }\n" +
            "    return -a;\n" +
            "}";
        return result.equals(exp);
    }


}
