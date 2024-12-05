import java.lang.reflect.Method;

public class DescriptorUtil {

    public static String getMethodDescriptor(String className, String methodName, Class<?>... parameterTypes) throws ClassNotFoundException, NoSuchMethodException {
        Class<?> clazz = Class.forName(className);
        Method method = clazz.getMethod(methodName, parameterTypes);
        StringBuilder descriptor = new StringBuilder("(");
        
        for (Class<?> paramType : method.getParameterTypes()) {
            descriptor.append(getTypeDescriptor(paramType));
        }
        
        descriptor.append(")");
        descriptor.append(getTypeDescriptor(method.getReturnType()));
        
        return descriptor.toString();
    }

    private static String getTypeDescriptor(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == int.class) return "I";
            if (type == void.class) return "V";
            if (type == boolean.class) return "Z";
            if (type == byte.class) return "B";
            if (type == char.class) return "C";
            if (type == short.class) return "S";
            if (type == long.class) return "J";
            if (type == float.class) return "F";
            if (type == double.class) return "D";
        } else if (type.isArray()) {
            return "[" + getTypeDescriptor(type.getComponentType());
        } else {
            return "L" + type.getName().replace('.', '/') + ";";
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }
}

