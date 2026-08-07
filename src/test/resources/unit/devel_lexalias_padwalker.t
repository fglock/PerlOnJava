use strict;
use warnings;
use Test::More tests => 12;
use Devel::Caller qw(caller_cv);
use Devel::LexAlias qw(lexalias);
use PadWalker qw(peek_sub);

sub target {
    my $scalar;
    my @array;
    my %hash;
    return $scalar, \@array, \%hash;
}

my $pad = peek_sub(\&target);
ok(exists $pad->{'$scalar'}, 'peek_sub reports a scalar lexical');
ok(exists $pad->{'@array'}, 'peek_sub reports an array lexical');
ok(exists $pad->{'%hash'}, 'peek_sub reports a hash lexical');

my $scalar = 42;
my @array = qw(alpha beta);
my %hash = (answer => 42);
lexalias(\&target, '$scalar', \$scalar);
lexalias(\&target, '@array', \@array);
lexalias(\&target, '%hash', \%hash);

my ($got_scalar, $got_array, $got_hash) = target();
is($got_scalar, 42, 'scalar lexical aliases a caller-provided cell');
is_deeply($got_array, [qw(alpha beta)], 'array lexical aliases a caller-provided cell');
is_deeply($got_hash, { answer => 42 }, 'hash lexical aliases a caller-provided cell');

sub replace_outer {
    my $replacement = 99;
    lexalias(1, '$outer', \$replacement);
}

sub outer {
    my $outer = 1;
    replace_outer();
    return $outer;
}

is(outer(), 99, 'numeric caller levels alias an active lexical frame');

my $anonymous;
$anonymous = sub {
    is(caller_cv(0), $anonymous, 'caller_cv returns the exact anonymous CV');
};
$anonymous->();

sub initialized_target {
    my $value = 7;
    return $value;
}
my $persistent = 3;
lexalias(\&initialized_target, '$value', \$persistent);
is(initialized_target(), 7, 'an initializer assigns through the alias');
is($persistent, 7, 'initializer updates the aliased external cell');

my $captured = 5;
my $closure = sub { return $captured };
my $replacement = 11;
ok(exists peek_sub($closure)->{'$captured'},
    'peek_sub reports a captured anonymous-sub lexical');
lexalias($closure, '$captured', \$replacement);
is($closure->(), 11, 'lexalias rebinds a captured anonymous-sub cell');
