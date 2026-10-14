use strict;
use warnings;
use Test::More;

my $source = <<'PERL';
sub DynamicNamedArrowProbe::invoke {
    my ($first, $second) = DynamicNamedArrowProbe::collect->('first', 'second');
    return "$first|$second";
}
1;
PERL

eval $source or die $@;
{
    no strict 'refs';
    *{'DynamicNamedArrowProbe::collect'} = sub { @_ };
}
is(DynamicNamedArrowProbe::invoke(), 'first|second',
    'unresolved fully qualified sub arrow call passes arguments to the late-bound sub');

done_testing;
