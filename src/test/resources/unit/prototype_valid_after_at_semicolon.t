use strict;
use warnings;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $ok = eval q{
        use warnings 'illegalproto';
        sub valid_array_with_optional_arguments (\@;@) { }
        1;
    };
    ok($ok, 'prototype \\@;@ compiles');
    is($@, '', 'valid \\@;@ prototype has no compile error');
}

is_deeply(\@warnings, [], 'valid \\@;@ prototype does not emit illegalproto');
done_testing();
