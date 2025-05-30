import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.llvm4j.llvm4j.Module;
import org.llvm4j.optional.Option;

import java.io.File;

public class utils
{
    static void dump(String path, LLVMModuleRef module)
    {
        new Module(module).dump(Option.of(new File(path)));
    }
}
