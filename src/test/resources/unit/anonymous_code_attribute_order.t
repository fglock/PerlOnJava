use strict;
use warnings;

{
    package AttributeOrder;

    our @seen;

    sub MODIFY_CODE_ATTRIBUTES {
        my ($package, $code, @attributes) = @_;
        push @seen, $code;
        return ();
    }

    our $anonymous = sub : Ordered { 1 };
    sub named : Ordered { 2 }
}

print "1..2\n";
print $AttributeOrder::seen[0] == $AttributeOrder::anonymous
    ? "ok 1 - anonymous CODE attributes are dispatched at compile time in source order\n"
    : "not ok 1 - anonymous CODE attributes are dispatched at compile time in source order\n";
print $AttributeOrder::seen[1] == \&AttributeOrder::named
    ? "ok 2 - following named CODE attribute is dispatched second\n"
    : "not ok 2 - following named CODE attribute is dispatched second\n";
