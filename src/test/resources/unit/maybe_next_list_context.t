use strict;
use warnings;
use Test::More;
use MRO::Compat ();

{
    package Issue1307::Options;

    sub option_data {
        my ($class) = @_;
        return $class->maybe::next::method, module => { format => 's' };
    }
}

my @option_data = Issue1307::Options->option_data;
is_deeply(
    \@option_data,
    [ module => { format => 's' } ],
    'maybe::next::method contributes no list element when no next method exists',
);

done_testing;
