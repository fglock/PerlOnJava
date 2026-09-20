use strict;
use Test::More;

my $stderr = '';
our @empty;
{
    local *STDERR;
    open STDERR, '>', \$stderr or die "open STDERR: $!";
    readline @empty;
}

is($stderr, '', 'unopened readline is silent when warnings are disabled');

done_testing;
