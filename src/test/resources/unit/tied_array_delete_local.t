use strict;
use warnings;

print "1..4\n";

{
    package LocalArray;
    sub TIEARRAY { bless [], shift }
    sub STORE { $_[0]->[$_[1]] = $_[2] }
    sub FETCH { $_[0]->[$_[1]] }
    sub FETCHSIZE { scalar @{$_[0]} }
    sub EXISTS { exists $_[0]->[$_[1]] }
    sub DELETE { delete $_[0]->[$_[1]] }
    sub CLEAR { @{$_[0]} = () }
    sub EXTEND { }
}

tie my @values, 'LocalArray';
@values = qw(a b c);
{
    my $deleted = delete local $values[1];
    print $deleted eq 'b' && !exists $values[1]
        ? "ok 1 - delete local returns and removes tied element\n"
        : "not ok 1 - delete local returns and removes tied element\n";

    delete local $values[888];
    $values[888] = 'temporary';
    print $values[888] eq 'temporary'
        ? "ok 2 - localized missing high element can be assigned\n"
        : "not ok 2 - localized missing high element can be assigned\n";
}

print $values[1] eq 'b'
    ? "ok 3 - existing tied element is restored\n"
    : "not ok 3 - existing tied element is restored\n";
print @values == 3 && !exists $values[888]
    ? "ok 4 - missing high tied element and size are restored\n"
    : "not ok 4 - missing high tied element and size are restored\n";
