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
                String tokenStr = SysYLexer.VOCABULARY.getSymbolicName(token.getType()) + " " + token.getText() + " at Line " + token.getLine() + ".";
                System.err.println(tokenStr);
            }
        }

    }


}
