import java.util.HashMap;
import java.util.Map;

class SymbolTable {
    private Map<String, Type> table = new HashMap<>();
    private SymbolTable parent; // 支持嵌套作用域

    public SymbolTable(SymbolTable parent) {
        this.parent = parent;
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
