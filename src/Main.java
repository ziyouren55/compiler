import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.llvm4j.llvm4j.Module;
import org.llvm4j.optional.Option;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

//java -jar .\lib\antlr-4.9.1-complete.jar -listener -visitor -long-messages .\src\SysYLexer.g4 .\src\SysYParser.g4
//java -jar rars.jar tests/output.asm a0
public class Main {

    public static void main(String[] args) throws IOException
    {
        if (args.length < 2) {
            System.err.println("input and output path is required");
        }
        String source = args[0];
        String output = args[1];
        String ll_output = "./tests/ll_out/output.ll";
        CharStream input = CharStreams.fromFileName(source);

        CommonTokenStream tokens = lexerAnalysis(input);
        SysYParser       parser = new SysYParser(tokens);
        ParseTree        tree   = parser.compUnit();

        // 生成LLVM IR
        LLVMIRVisitor llvmirVisitor = new LLVMIRVisitor();
        llvmirVisitor.visit(tree);

        Module module = llvmirVisitor.getMod();
//        module.dump(Option.of(new File(ll_output)));

        // 生成RISC-V汇编代码
        RISCVCGVisitor riscvVisitor = new RISCVCGVisitor(module);
        String asmCode = riscvVisitor.generateCode();

        // 写入输出文件
        try (FileWriter writer = new FileWriter(output)) {
            writer.write(asmCode);
        }

    }


    public static CommonTokenStream lexerAnalysis(CharStream input)
    {
        SysYLexer sysYLexer = new SysYLexer(input);

        MyErrorListener errorListener = new MyErrorListener();
        sysYLexer.removeErrorListeners();
        sysYLexer.addErrorListener(errorListener);

        CommonTokenStream tokens = new CommonTokenStream(sysYLexer);
        tokens.fill(); // 预加载所有 token

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



}
