use strict;
use warnings;
use Test::More;

my (@warnings, $result);
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $result = eval <<'PERL';
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';
use warnings;
my sub lexical_call { 'bound' }
sub named_lexical_caller { lexical_call() }
named_lexical_caller();
PERL
    die $@ if $@;
}

is($result, 'bound', 'named sub retains its file-scope lexical sub binding');
is_deeply(\@warnings, [], 'file-scope lexical binding does not produce an unavailable warning');

done_testing();
