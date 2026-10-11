use strict;
use warnings;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $ok = eval q{
        use warnings 'illegalproto';
        sub valid_array_with_optional_arguments (\@;@) { }
        sub valid_array_ref_and_array (\@@) { }
        sub valid_array_ref_and_scalar (\@$) { }
        1;
    };
    ok($ok, 'valid prototypes compile');
    is($@, '', 'valid prototypes have no compile error');
}

is_deeply(\@warnings, [], 'valid prototypes do not emit illegalproto');
done_testing();
