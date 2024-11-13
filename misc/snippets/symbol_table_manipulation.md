Perl symbol table manipulation using typeglobs

- $constant:: refers to the symbol table (stash) of the package

- {_CAN_PCS} is accessing a slot in that symbol table

- \$const creates a reference to the scalar variable $const

The overall effect is binding a constant at compile time that can be accessed without a sigil

Example:

```
package MyPackage;

# Create a scalar
my $value = 42;
Internals::SvREADONLY($value, 1);

# Bind it in the symbol table
$MyPackage::{CONSTANT} = \$value;
```

Also:

```
# Create a scalar
my $value = 42;
Internals::SvREADONLY($value, 1);

no strict 'refs';
$symtab = \%{$pkg . '::'};

$symtab->{$name} = \$value;
```

Also:

```
$symtab->{$name} = \@list;
```



