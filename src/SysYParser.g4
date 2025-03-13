parser grammar SysYParser;

options { tokenVocab=SysYLexer; }

compUnit
    : (decl | funcDef)* EOF
    ;

decl
    : constDecl
    | varDecl
    ;

constDecl
    : 'const' bType constDef (',' constDef)* ';'
    ;

bType
    : 'int'
    ;

constDef
    : IDENT ('[' constExp ']')* '=' constInitVal
    ;

constInitVal
    : constExp
    | '{' (constInitVal (',' constInitVal)*)? '}'
    ;

varDecl
    : bType varDef (',' varDef)* ';'
    ;

varDef
    : IDENT ('[' constExp ']')* ('=' initVal)?
    ;

initVal
    : exp
    | '{' (initVal (',' initVal)*)? '}'
    ;

funcDef
    : funcType IDENT '(' (funcFParams)? ')' block
    ;

funcType
    : 'void'
    | 'int'
    ;

funcFParams
    : funcFParam (',' funcFParam)*
    ;

funcFParam
    : bType IDENT ('[' ']' ('[' exp ']')*)?
    ;

block
    : '{' blockItem* '}'
    ;

blockItem
    : decl
    | stmt
    ;

stmt
    : lVal '=' exp ';'                      // 赋值语句
    | exp? ';'                              // 表达式语句（可空）
    | block
    | 'if' '(' cond ')' stmt ('else' stmt)?  // if 语句（带或不带 else）
    | 'while' '(' cond ')' stmt              // while 语句
    | 'break' ';'
    | 'continue' ';'
    | 'return' exp? ';'
    ;

exp
    : addExp
    ;

cond
    : lOrExp
    ;

lVal
    : IDENT ('[' exp ']')*
    ;

primaryExp
    : '(' exp ')'
    | lVal
    | INTEGER_CONST
    ;

unaryExp
    : primaryExp
    | IDENT '(' (funcRParams)? ')'  // 函数调用
    | unaryOp unaryExp
    ;

unaryOp
    : '+'
    | '-'
    | '!'
    ;

funcRParams
    : exp (',' exp)*
    ;

mulExp
    : unaryExp (('*' | '/' | '%') unaryExp)*
    ;

addExp
    : mulExp (('+' | '-') mulExp)*
    ;

relExp
    : addExp (('<' | '>' | '<=' | '>=') addExp)*
    ;

eqExp
    : relExp (('==' | '!=') relExp)*
    ;

lAndExp
    : eqExp ('&&' eqExp)*
    ;

lOrExp
    : lAndExp ('||' lAndExp)*
    ;

constExp
    : addExp
    ;
