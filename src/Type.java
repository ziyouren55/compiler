import java.util.ArrayList;
import java.util.List;

public abstract class Type
{
}

class VoidType extends Type{
    static VoidType getVoidType(){
        return new VoidType();
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null && obj.getClass() == VoidType.class;
    }
}

class IntType extends Type {
    static IntType getI32() {
        return new IntType();
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null && obj.getClass() == IntType.class;
    }
}

class ArrayType extends Type {
    Type contained; // 数组元素的类型
    int numElements; // 数组的元素数量

    ArrayType(Type contained, int numElements) {
        this.contained = contained;
        this.numElements = numElements;
    }

     @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ArrayType other = (ArrayType) obj;
        return numElements == other.numElements && contained.equals(other.contained);
    }

    public Type getContained()
    {
        return contained;
    }

    public int getNumElements(){
        return numElements;
    }
}

class FunctionType extends Type {
    Type retType; // 返回值类型
    List<Type> paramsType; // 参数类型

    FunctionType(Type retType, List<Type> paramsType) {
        this.retType = retType;
        this.paramsType = paramsType;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        FunctionType other = (FunctionType) obj;
        return retType.equals(other.retType) && paramsType.equals(other.paramsType);
    }

    public List<Type> getParamsType(){return paramsType;}
    public Type getRetType(){return retType;}
}
