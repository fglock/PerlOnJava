use strict;
use warnings;
use Test::More;
use Scalar::Util qw(reftype);

my $tied = tie $::{__perlonjava_scalar_tie_probe}, 'StashScalarTieProbe';
isa_ok($tied, 'StashScalarTieProbe', 'stash scalar invokes TIESCALAR');
is($StashScalarTieProbe::constructor, 'TIESCALAR', 'stash scalar did not invoke TIEHANDLE');
my $glob_ref = \*main::__perlonjava_explicit_glob_control;
is(reftype($glob_ref), 'GLOB', 'explicit glob references remain glob references');

done_testing;

package StashScalarTieProbe;

our $constructor;

sub TIESCALAR {
    $constructor = 'TIESCALAR';
    return bless {}, shift;
}

sub FETCH { return 'probe' }
sub STORE { return }
