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
        SysYParser       parser = new SysYParser(tokens);
        ParseTree        tree   = parser.compUnit();

        LLVMIRVisitor llvmirVisitor = new LLVMIRVisitor();
        llvmirVisitor.visit(tree);

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
//        if (errorListener.hasErrors()) {
//            errorListener.printParserErrorInformation();
//        }
//        else
//        {
            FormaterVisitor formatter = new FormaterVisitor();
            String formattedCode = formatter.visit(tree);
            if(formattedCode.equals("false"))
                OutputHelper.printNoSemanticErrors();
            //shan
//            System.out.println(check(formattedCode));
//        }
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
        String exp = "const int globalArray[2][3] = {{1 + 2, 3 * 4, 5 / 6}, {7 - 8, 9 % 10, 0}};\n" +
            "\n" +
            "int add(int a, int b) {\n" +
            "    return a + b;\n" +
            "}\n" +
            "\n" +
            "void process(int x) {\n" +
            "    int arr[3] = {x, x * 2, x + 3};\n" +
            "    if (arr[0] > 0) {\n" +
            "        while (arr[1] < 100) {\n" +
            "            arr[1] = arr[1] * 2;\n" +
            "            if (arr[1] > 50) {\n" +
            "                break;\n" +
            "            }\n" +
            "        }\n" +
            "    }\n" +
            "    else {\n" +
            "        arr[2] = arr[2] - 1;\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "int complexExp(int a, int b) {\n" +
            "    int c = (a + b) * (a - b) % 10;\n" +
            "    return c * d + a / (b + 1);\n" +
            "}\n" +
            "\n" +
            "int main() {\n" +
            "    int a = 10;\n" +
            "    int b = 20;\n" +
            "    int result[5];\n" +
            "\n" +
            "    if (a > 0) {\n" +
            "        if (b < 30) {\n" +
            "            result[0] = add(a, b);\n" +
            "        }\n" +
            "        else {\n" +
            "            result[0] = complexExp(a, b);\n" +
            "        }\n" +
            "    }\n" +
            "    else {\n" +
            "        result[0] = 0;\n" +
            "    }\n" +
            "\n" +
            "    while (a < 100) {\n" +
            "        a = a * 2;\n" +
            "        if (a % 3 == 0) {\n" +
            "            continue;\n" +
            "        }\n" +
            "        result[1] = result[1] + a;\n" +
            "    }\n" +
            "\n" +
            "    process(result[0]);\n" +
            "    return result[0] + result[1];\n" +
            "}";
        return result.equals(exp);
    }


}
