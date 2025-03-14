lexer grammar SysYLexer;

// 关键字
CONST    : 'const';
INT      : 'int';
VOID     : 'void';
IF       : 'if';
ELSE     : 'else';
WHILE    : 'while';
BREAK    : 'break';
CONTINUE : 'continue';
RETURN   : 'return';

// 操作符
PLUS     : '+';
MINUS    : '-';
MUL      : '*';
DIV      : '/';
MOD      : '%';
ASSIGN   : '=';
EQ       : '==';
NEQ      : '!=';
LT       : '<';
GT       : '>';
LE       : '<=';
GE       : '>=';
NOT      : '!';
AND      : '&&';
OR       : '||';

// 分隔符
L_PAREN  : '(';
R_PAREN  : ')';
L_BRACE  : '{';
R_BRACE  : '}';
L_BRACKT : '[';
R_BRACKT : ']';
COMMA    : ',';
SEMICOLON: ';';

// 标识符
IDENT    : [a-zA-Z_][a-zA-Z_0-9]*;

 //数字常量（支持前导 0）
INTEGER_CONST :  '0x' [0-9a-fA-F]+ {
    setText(String.valueOf(Integer.parseInt(getText().substring(2).replaceFirst("^0+", ""), 16)));
}
        | '0' [0-7]+ {
    setText(String.valueOf(Integer.parseInt(getText().substring(1).replaceFirst("^0+", "0"), 8)));
}
        | [0-9]+;



// 空白符，跳过
WS       : [ \r\n\t]+ -> skip;

// 单行注释，跳过
LINE_COMMENT
    : '//' ~[\r\n]* -> skip;

// 多行注释，跳过
MULTILINE_COMMENT
    : '/*' .*? '*/' -> skip;

// 非法字符捕获
//ERROR_CHAR : . -> channel(HIDDEN);
