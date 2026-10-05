use strict;
use warnings;
use Test::More;

my $warnings = '';
{
    local $SIG{__WARN__} = sub { $warnings .= join '', @_ };
    eval q{use warnings; sub prototype_optional_after_array (\@;@) { }};
}

is($warnings, '', 'the optional-argument separator after a reference prototype is valid');

done_testing;
