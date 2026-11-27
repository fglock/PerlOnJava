use strict;
use warnings;
use Test::More tests => 9;

# An anonymous [...] or {...} constructor returns a mortal reference. When no
# Perl slot takes ownership by the end of the statement, the aggregate and its
# contents are freed then; a literal that is stored or returned stays alive.

my @events;
{
    package Probe;
    sub new { my ($class, $name) = @_; bless { name => $name }, $class }
    sub DESTROY { push @events, $_[0]{name} }
}

my $n = scalar @{ [ Probe->new('array deref') ] };
is_deeply(\@events, ['array deref'], 'dereferenced array literal is freed at statement end');

@events = ();
my $last = $#{ [ Probe->new('last index') ] };
is_deeply(\@events, ['last index'], 'array literal under $# is freed at statement end');

@events = ();
my $keys = scalar keys %{ { k => Probe->new('hash deref') } };
is_deeply(\@events, ['hash deref'], 'dereferenced hash literal is freed at statement end');

@events = ();
my $elem = ${ [ Probe->new('element copy') ] }[0];
is_deeply(\@events, [], 'a copied element keeps its referent alive');
undef $elem;
is_deeply(\@events, ['element copy'], 'the copied element releases its referent');

@events = ();
sub make_array { [ Probe->new('returned array') ] }
my $kept = make_array();
is_deeply(\@events, [], 'a returned array literal survives the call boundary');
undef $kept;
is_deeply(\@events, ['returned array'], 'the returned array literal is freed with its last owner');

@events = ();
sub make_hash { return { p => Probe->new('returned hash') } }
my %store;
$store{x} = make_hash();
is_deeply(\@events, [], 'a returned hash literal stored in a hash survives');
delete $store{x};
is_deeply(\@events, ['returned hash'], 'deleting the stored hash literal frees it');
