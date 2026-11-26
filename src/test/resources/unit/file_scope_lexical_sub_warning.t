use strict;
use warnings;
use Test::More;

my @warnings;
my $result;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $result = eval <<'PERL';
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';
use warnings;
my sub file_scope_helper { 1 }
sub file_scope_named_caller { file_scope_helper() }
file_scope_named_caller();
PERL
    die $@ if $@;
}

is($result, 1, 'named sub calls the file-scope lexical sub');
is_deeply(\@warnings, [], 'file-scope lexical sub does not produce an unavailable warning');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval <<'PERL';
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';
use warnings;
sub nested_scope {
    my sub nested_helper { 1 }
    sub nested_named_caller { nested_helper() }
}
PERL
    die $@ if $@;
}
like(join('', @warnings), qr/Subroutine "&nested_helper" will not stay shared/,
    'runtime-scoped lexical sub retains its lifetime warning');

done_testing();
