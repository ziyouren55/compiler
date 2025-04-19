import org.llvm4j.llvm4j.Value;

import java.util.HashMap;
import java.util.Map;

class SymbolTableLLVM {
    private Map<String, Value> table = new HashMap<>();
    private SymbolTableLLVM parent; // 支持嵌套作用域

    public SymbolTableLLVM(SymbolTableLLVM parent) {
        this.parent = parent;
    }

    // 合并另一个 SymbolTable 的 map 到本对象中
    public void merge(SymbolTableLLVM other) {
        if (other != null && other.table != null) {
            // putAll 会把 other.table 中的所有键值对加入到当前 table 中
            // 如果存在相同的 key，则当前 table 中的值会被覆盖
            this.table.putAll(other.table);
        }
    }


    public void put(String name, Value value) {
        table.put(name, value);
    }

    public Value find(String name) {
        Value value = table.get(name);
        if (value == null && parent != null) {
            return parent.find(name); // 父作用域查找
        }
        return value;
    }

    public Value localFind(String name)
    {
        return table.get(name);
    }

    public SymbolTableLLVM getParent()
    {
        return parent;
    }
}
