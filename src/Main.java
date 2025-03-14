import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.*;

import java.io.IOException;
import java.util.List;

public class Main {

    public static void main(String[] args) throws IOException
    {
        if (args.length < 1) {
            System.err.println("input path is required");
        }
        String source = args[0];
        CharStream input = CharStreams.fromFileName(source);
        SysYLexer sysYLexer = new SysYLexer(input);

        MyErrorListener errorListener = new MyErrorListener();
        sysYLexer.removeErrorListeners();
        sysYLexer.addErrorListener(errorListener);

        List<?extends Token> tokens = sysYLexer.getAllTokens();

        int index = 0;

        if(errorListener.hasErrors())
        {
            errorListener.printLexerErrorInformation();
        }
        else
        {
            for (Token token : tokens)
            {
                String text = token.getText();
                if(token.getType() == SysYLexer.INTEGER_CONST)
                {
                    text = convertToDecimal(text);
                }
                String tokenStr = SysYLexer.VOCABULARY.getSymbolicName(token.getType()) + " " + text + " at Line " + token.getLine() + ".";
                System.err.println(tokenStr);
            }
        }

    }

    public static String convertToDecimal(String input) {
        if (input.startsWith("0x") || input.startsWith("0X")) {
            // 十六进制转换为十进制，并返回字符串
            return String.valueOf(Integer.parseInt(input.substring(2), 16));
        } else if (input.startsWith("0") && input.length() > 1) {
            // 八进制转换为十进制，并返回字符串
            return String.valueOf(Integer.parseInt(input.substring(1), 8));
        } else {
            // 十进制数保持原样，并返回字符串
            return input;  // 因为已经是十进制格式，直接返回原始字符串
        }
    }


}
