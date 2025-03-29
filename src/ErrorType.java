public enum ErrorType {
    // 定义错误类型和对应的错误信息模板
    UNDEFINED_VAR(1, "Undefined variable: %s"),         // 变量未声明
    UNDEFINED_FUNC(2, "Undefined function: %s"),             // 函数未定义
    REDEFINED_VAR(3, "Redefined variable: %s"),                  // 变量重复声明
    REDEFINED_FUNC(4, "Redefined function: %s"),                 // 函数重复定义
    MISMATCH_ASSIGN(5, "Mismatch types in assignment"),      // 赋值两侧类型不匹配
    INVALID_OPERATOR(6, "Invalid operator usage"),           // 运算符需求类型与提供类型不匹配
    FUNC_RETURN_TYPE_MISMATCH(7, "Return type mismatch in function: %s"), // 返回类型不匹配
    FUNC_PARAM_MISMATCH(8, "Function parameter mismatch: %s"), // 函数参数不适用
    NON_ARRAY_SUBSCRIPT(9, "Subscript operator used on non-array variable: %s"), // 对非数组使用下标运算符
    VAR_USED_AS_FUNC(10, "Variable used as a function: %s"), // 对变量使用函数调用
    ASSIGN_TO_NON_VAR(11, "Assignment to non-variable or array element: %s"); // 赋值号左侧非变量或数组元素

    private final int errorCode;  // 错误类型编号
    private final String errorMessageTemplate;  // 错误信息模板

    // 构造函数
    ErrorType(int errorCode, String errorMessageTemplate) {
        this.errorCode = errorCode;
        this.errorMessageTemplate = errorMessageTemplate;
    }

    // 获取错误类型编号
    public int getErrorCode() {
        return errorCode;
    }

    public String getErrorMessageTemplate() {
        return errorMessageTemplate;
}
}
