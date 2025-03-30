import java.util.HashMap;
import java.util.Map;

class SymbolTable {
    private Map<String, Type> table = new HashMap<>();
    private SymbolTable parent; // 支持嵌套作用域

    public SymbolTable(SymbolTable parent) {
        this.parent = parent;
    }

    // 合并另一个 SymbolTable 的 map 到本对象中
    public void merge(SymbolTable other) {
        if (other != null && other.table != null) {
            // putAll 会把 other.table 中的所有键值对加入到当前 table 中
            // 如果存在相同的 key，则当前 table 中的值会被覆盖
            this.table.putAll(other.table);
        }
    }


    public void put(String name, Type type) {
        table.put(name, type);
    }

    public Type find(String name) {
        Type type = table.get(name);
        if (type == null && parent != null) {
            return parent.find(name); // 父作用域查找
        }
        return type;
    }

    public Type localFind(String name)
    {
        return table.get(name);
    }

    public SymbolTable getParent()
    {
        return parent;
    }
}
