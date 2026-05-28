use strict;
use warnings;
use Test::More;

our $foo = 42;
my $slot = *foo{SCALAR};

is(ref($slot), 'SCALAR', '*glob{SCALAR} returns a scalar reference');
is($$slot, 42, 'scalar slot reference points at package scalar');

use Exporter ();
{
    no strict 'refs';
    for my $name (qw(Debug ExportLevel VERSION Verbose)) {
        my $ref = *{"Exporter::$name"}{SCALAR};
        ok(!defined($ref) || ref($ref) eq 'SCALAR', "Exporter::$name SCALAR slot is undef or scalar ref");
    }
}

done_testing;
