use strict;
use warnings;
use Test::More;

{
    package CommandLineCategory;
    use warnings::register;

    sub emit_warning {
        warnings::warnif('command line W payload');
    }
}

my @warnings;
{
    no warnings 'CommandLineCategory';
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    CommandLineCategory::emit_warning();
}
is(scalar @warnings, 1, '-W overrides lexical custom-category suppression');
like($warnings[0] // '', qr/^command line W payload/,
    '-W warning retains its payload');

done_testing;
