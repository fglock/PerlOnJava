
package MyTest;

our @EXPORT_OK = qw{
    foo
    bar
    baz
};

print "Size: " . scalar(@EXPORT_OK) . "\n";
print "Content: " . join(", ", @EXPORT_OK) . "\n";

1;
