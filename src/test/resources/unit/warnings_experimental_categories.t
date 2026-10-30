use strict;
use warnings;
use Test::More;

sub warning_bits_at_call_site {
    return (caller(0))[9];
}

{
    use warnings 'experimental::for_list';
    ok(defined warning_bits_at_call_site(),
        'experimental::for_list is accepted by use warnings');
}

{
    no warnings 'experimental::for_list';
    pass('experimental::for_list is accepted by no warnings');
}

my $builtin_bits;
{
    use warnings 'experimental::builtin';
    $builtin_bits = warning_bits_at_call_site();
}
ok(vec($builtin_bits, $warnings::Offsets{'experimental::builtin'}, 1),
    'experimental::builtin sets its Perl warning bit');

{
    no warnings 'experimental::builtin';
    $builtin_bits = warning_bits_at_call_site();
}
ok(!vec($builtin_bits, $warnings::Offsets{'experimental::builtin'}, 1),
    'no warnings clears the experimental::builtin bit');

{
    use warnings FATAL => 'experimental::builtin';
    $builtin_bits = warning_bits_at_call_site();
}
ok(vec($builtin_bits, $warnings::Offsets{'experimental::builtin'} + 1, 1),
    'fatal experimental::builtin sets its fatal bit');

require Data::Dumper;
ok($Data::Dumper::VERSION, 'Data::Dumper loads with experimental::builtin declarations');

done_testing;
