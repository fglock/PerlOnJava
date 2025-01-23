To create a Set that is a combination of two or more existing Sets:

Create a new HashSet by passing all elements from the existing sets:

```
private static final Set<String> COMBINED_SET;

static {
    Set<String> combinedSet = new HashSet<>(LISTTERMINATORS);
    combinedSet.addAll(UNARY_OP);
    COMBINED_SET = Collections.unmodifiableSet(combinedSet);
}
```

Alternatively, you can use Java 8 streams to create a combined set:

```
private static final Set<String> COMBINED_SET = Stream.concat(LISTTERMINATORS.stream(), UNARY_OP.stream())
        .collect(Collectors.toSet());
```
