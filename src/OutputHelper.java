public class OutputHelper {

    boolean isWrong = false;
    // 输出语义错误信息的方法
     public void printSemanticError(ErrorType errorType, int line, String name) {
        // 使用错误类型的编号和模板输出错误信息
        String errorMessage = String.format("Error type %d at Line %d: " + errorType.getErrorMessageTemplate(),
                                           errorType.getErrorCode(), line, name);
        System.err.println(errorMessage);
        isWrong = true;
    }

    // 没有语义错误时输出提示
    public static void printNoSemanticErrors() {
        System.err.println("No semantic errors in the program!");
    }
}

