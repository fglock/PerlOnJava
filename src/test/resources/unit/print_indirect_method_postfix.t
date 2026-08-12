use strict;
use warnings;

{
    package PrintIndirectFactory;

    sub create {
        my ($class, $code) = @_;
        return bless { code => $code }, $class;
    }

    sub join { return $_[0]->{code}->() }
}

print "1..1\n";
print create PrintIndirectFactory sub {
    return sub { return sub { return "ok 1 - print preserves an indirect postfix call chain\n" } };
}=>->join->()();
