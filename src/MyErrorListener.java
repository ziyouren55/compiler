import org.antlr.v4.runtime.*;

import java.util.ArrayList;
import java.util.List;

public class MyErrorListener extends BaseErrorListener
{
    private final List<String> errors = new ArrayList<>();

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e)
    {
        String errorMessage;
        if (msg.startsWith("token recognition error at:"))
        {
            errorMessage = String.format("Mysterious character \"%s\"" + ".", msg.substring(29,msg.length() - 1));
        }
        else
        {
            errorMessage = msg;
        }

        if (recognizer instanceof Lexer) {
            errors.add(String.format("Error type A at Line %d: %s", line, errorMessage));
        } else {
            errors.add(String.format("Error type B at Line %d: %s", line, errorMessage));
        }
    }

    public boolean hasErrors()
    {
        return !errors.isEmpty();
    }

    public void printLexerErrorInformation()
    {
        for (String error : errors) {
            if (error.startsWith("Error type A"))
                System.err.println(error);
        }
    }

    public void printParserErrorInformation()
    {
        for (String error : errors) {
            if (error.startsWith("Error type B"))
                System.out.println(error);
        }
    }
}

