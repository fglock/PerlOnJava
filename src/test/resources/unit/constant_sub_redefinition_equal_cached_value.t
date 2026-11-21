use strict;
use warnings;
use Test::More tests => 4;

{
    package Issue2075::Exporter;
    our %DEFINED;

    sub import {
        my ($class) = @_;
        my $importer = caller;
        my $private_name = "BLAH_$main::BLAH_NAME";

        no strict 'refs';
        unless ($DEFINED{$private_name}) {
            my $value_copy = 'same constant value';
            *{"${class}::$private_name"} = sub () { $value_copy };
            $DEFINED{$private_name} = 1;
        }
        *{"${importer}::BLAH"} = \&{"${class}::$private_name"};
    }
}

$main::BLAH_NAME = 'FOO';
my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    Issue2075::Exporter->import;
}
is($warning, '', 'first constant import does not warn');
is(BLAH(), 'same constant value', 'first private wrapper is imported');

$warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    Issue2075::Exporter->import;
}
is($warning, '', 're-exporting the same wrapper does not warn');

$main::BLAH_NAME = 'BAR';
$warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    Issue2075::Exporter->import;
}
like($warning, qr/^Constant subroutine main::BLAH redefined/,
    'a distinct private wrapper warns even when its cached value is unchanged');
