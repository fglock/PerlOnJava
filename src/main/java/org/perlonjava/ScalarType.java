// Enum to represent the type of value stored in the scalar
public enum ScalarType {
  INTEGER,
  DOUBLE,
  STRING,
  CODE,
  UNDEF,
  REFERENCE,
  ARRAYREFERENCE,
  HASHREFERENCE
  // also BLESSED and special literals like filehandles, typeglobs, and regular expressions
}
