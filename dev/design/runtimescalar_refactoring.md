
ScalarTypeHandler:
Purpose: Handle operations specific to different scalar types.
Functions:

getInt()
getDouble()
getString()
getBoolean()
parseNumber()

ArithmeticOperations:
Purpose: Encapsulate arithmetic operations for scalars.
Functions:

add(RuntimeScalar a, RuntimeScalar b)
subtract(RuntimeScalar a, RuntimeScalar b)
multiply(RuntimeScalar a, RuntimeScalar b)
divide(RuntimeScalar a, RuntimeScalar b)
modulus(RuntimeScalar a, RuntimeScalar b)

StringOperations:
Purpose: Manage string-specific operations.
Functions:

lcfirst(String str)
ucfirst(String str)
stringConcat(String a, String b)
stringIncrement(String str)

ReferenceHandler:
Purpose: Handle reference and dereference logic.
Functions:

hashDerefGet(RuntimeScalar scalar, RuntimeScalar index)
arrayDerefGet(RuntimeScalar scalar, RuntimeScalar index)
scalarDeref(RuntimeScalar scalar)
globDeref(RuntimeScalar scalar)

IOOperations:
Purpose: Manage I/O-related operations.
Functions:

rmdir(String dirName)
closedir(RuntimeIO dirIO)
rewinddir(RuntimeIO dirIO)
telldir(RuntimeIO dirIO)

MathOperations:
Purpose: Handle mathematical operations.
Functions:

log(double value)
sqrt(double value)
cos(double value)
sin(double value)
pow(double base, double exponent)

