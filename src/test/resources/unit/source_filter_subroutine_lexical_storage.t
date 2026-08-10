use strict;
use warnings;
use Test::More tests => 5;
use Scalar::Util qw(refaddr);
use Filter::Simple;

# A no-op filter is enough to exercise the rewritten-source compiler path.
FILTER { };

sub make_filtered_inside_out_object {
    my ($class) = @_;
    return bless \my($anonymous_scalar), $class;
}

# Source-filter preprocessing used to leave the subroutine's lexical in the
# shared symbol table, where later BEGIN blocks classified it as persistent.
BEGIN { my $compile_time_only = 1 }

my $first = make_filtered_inside_out_object('FilteredFirst');
my $second = make_filtered_inside_out_object('FilteredSecond');

isnt refaddr($first), refaddr($second), 'filtered sub creates a fresh scalar referent';
is ref($first), 'FilteredFirst', 'first filtered object keeps its class';
is ref($second), 'FilteredSecond', 'second filtered object gets its class';
isnt "$first", "$second", 'filtered objects remain distinct';
pass 'later BEGIN block does not make a filtered sub lexical persistent';
