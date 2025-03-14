import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.IOException;

//java -jar .\lib\antlr-4.9.1-complete.jar -listener -visitor -long-messages .\src\SysYLexer.g4 .\src\SysYParser.g4

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
            //System.out.println(check(formattedCode));
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
//                if (token.getType() == SysYLexer.EOF)
//                {
//                    continue;  // Skip EOF token
//                }
//
//                String tokenText = token.getText();
//                if(token.getType() == SysYLexer.INTEGER_CONST)
//                {
//                    tokenText = convertToDecimal(tokenText);
//                }
//                String tokenStr = SysYLexer.VOCABULARY.getSymbolicName(token.getType()) + " " + tokenText + " at Line " + token.getLine() + ".";
//                System.err.println(tokenStr);
//            }
//        }

        return tokens;
    }

    public static String convertToDecimal(String input) {
        if (input.startsWith("0x") || input.startsWith("0X")) {
            // 处理十六进制
            return String.valueOf(Integer.parseInt(input.substring(2), 16));
        } else if (input.startsWith("0") && input.length() > 1) {
            // 处理八进制
            return String.valueOf(Integer.parseInt(input.substring(1), 8));
        } else {
            // 处理十进制
            return input;
        }
    }

    public static boolean check(String result)
    {
        String exp = "void main() {\n" +
            "    int i = 0;\n" +
            "    while (i < 10) {\n" +
            "        if (i % 2 == 0) {\n" +
            "            x = x + i;\n" +
            "        }\n" +
            "        else {\n" +
            "            y[i] = x * i;\n" +
            "        }\n" +
            "        i = i + 1;\n" +
            "    }\n" +
            "    return;\n" +
            "}\n" +
            "const int a = 10;\n" +
            "const int b = 20;\n" +
            "const int arr[2] = {1, 2};\n" +
            "int x = 5;\n" +
            "int y[10];\n" +
            "int z = {1, 2, 3};\n" +
            "\n" +
            "int sum(int x, int y) {\n" +
            "    return x + y;\n" +
            "}\n" +
            "\n" +
            "void test() {\n" +
            "    int result = sum(a, b);\n" +
            "    if (result > 10) {\n" +
            "        result = result - 10;\n" +
            "    }\n" +
            "    else if (result < 20) {\n" +
            "        result = result + 10;\n" +
            "    }\n" +
            "    return;\n" +
            "}\n" +
            "\n" +
            "int factorial(int n) {\n" +
            "    if (n <= 1) {\n" +
            "        return 1;\n" +
            "    }\n" +
            "    else {\n" +
            "        return n * factorial(n - 1);\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "void calculate() {\n" +
            "    int num = 5;\n" +
            "    int fact = factorial(num);\n" +
            "    while (count < max) {\n" +
            "        count = count + 1;\n" +
            "    }\n" +
            "    return;\n" +
            "}";
        return result.equals(exp);
    }


}
